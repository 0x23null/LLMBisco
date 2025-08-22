package controller;

import java.awt.Point;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import model.LLM;

public class Statistic {

    // ===== GIỮ NGUYÊN TOẠ ĐỘ + SCALE CỦA BẠN =====
    private final Point first = new Point(408, 591);
    private final Point last = new Point(880, 590);
    private final double screenScale = 1.0;

    // ===== NHỊP & GIÁM SÁT =====
    private final long pollIntervalMs = 2000;   // quét mỗi 2s
    private final long heartbeatMs = 10_000; // in heartbeat mỗi 10s khi chờ
    private final long stallWarnMs = 90_000; // >90s không thấy round close -> cảnh báo

    // ===== STABILITY WINDOW (K lần đọc giống hệt) =====
    private final int requiredStable = 2;      // K=2 là "đủ mượt"; có thể đổi 3 nếu UI nháy nhiều
    private String prevHistory = null;   // lịch sử đã XÁC NHẬN gần nhất
    private String candidateHistory = null;   // ứng viên lịch sử đang ổn định
    private int stableCount = 0;      // số lần thấy liên tiếp candidateHistory

    // ===== LLM & PREDICTION =====
    private final LLM llm = new LLM();
    private Pred pendingPred = null;   // kèo đã "đặt" cho ván đang chạy, sẽ settle khi close
    private final boolean enableRetryOnSkip = true;   // thử gọi lại 1 lần nếu ra SKIP
    private final boolean enableHeuristicFallback = true; // fallback local khi vẫn SKIP (mặc định tắt)

    // ===== BANKROLL (MARTINGALE) =====
    private final double baseBet = 3.0;       // cược gốc (bạn đổi được)
    private final double multiplier = 2.0;       // x2 (đổi 3.0 nếu muốn x3)
    private final Double stakeCap = null;      // null = không giới hạn
    private final Bankroll bankroll = new Bankroll(baseBet, multiplier, stakeCap);

    // ===== GIỚI HẠN SỐ VÁN =====
    private final int maxRounds;

    // ===== THỐNG KÊ / LOG =====
    private int settledRounds = 0;       // số ván đã settle (đã có actual)
    private int correctOnBets = 0;       // số ván đúng trên các ván CÓ đặt (không tính SKIP)
    private int pTAI_aT = 0, pTAI_aX = 0, pXIU_aX = 0, pXIU_aT = 0, pSKIP_aT = 0, pSKIP_aX = 0;
    private int heuristicFallbackUsedCount = 0;
    private int heuristicFallbackCorrectCount = 0;

    private long lastHeartbeatAtMs = 0L;
    private long lastChangeAtMs = 0L;
    private final DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Regex: "pick": "TAI|XIU|SKIP"
    private static final Pattern PICK_RE = Pattern.compile("\"pick\"\\s*:\\s*\"\\s*(TAI|XIU|SKIP)\\s*\"", Pattern.CASE_INSENSITIVE);

    public Statistic() {
        this(10);
    }

    public Statistic(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public void run() {
        // Start scanner đúng 1 lần
        DotScannerService scan = new DotScannerService(first, last, screenScale);
        scan.start();
        log("[INFO] Statistic started. Poll=" + pollIntervalMs + "ms, requiredStable=" + requiredStable);

        while (true) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log("[ERR ] loop interrupted");
                break;
            }

            String history = scan.getLastHistory();
            if (history == null || history.length() < 13) {
                heartbeat();
                continue;
            }

            // === STABILITY WINDOW ===
            if (!history.equals(candidateHistory)) {
                candidateHistory = history;
                stableCount = 1;
                //log("[DBG ] candidate changed; reset stableCount=1");
            } else {
                stableCount++;
                //log("[DBG ] stableCount=" + stableCount + "/" + requiredStable);
            }

            if (stableCount < requiredStable) {
                heartbeat();
                continue;
            }
            // Tới đây: candidateHistory đã ổn định đủ K lần
            boolean firstConfirm = (prevHistory == null);
            boolean closed = !firstConfirm && !candidateHistory.equals(prevHistory);

            if (firstConfirm) {
                // Lần đầu có lịch sử ổn định: chưa settle gì, chỉ xin kèo cho ván kế tiếp
                pendingPred = callLLMForPick(candidateHistory);
                System.out.printf("[%s] NEW SESS | hist=%s | pred_next=%s | nextStake=%.2f%n",
                        nowStr(), candidateHistory, pendingPred, bankroll.getCurrentStake());
                prevHistory = candidateHistory;
                lastChangeAtMs = System.currentTimeMillis();
                continue;
            }

            if (closed) {
                // 1) SETTLE kèo cũ với actual = ký tự cuối của lịch sử đã ổn định mới
                Actual actual = (candidateHistory.charAt(candidateHistory.length() - 1) == 'T') ? Actual.T : Actual.X;
                if (pendingPred != null) {
                    settledRounds++;
                    settleAndReport(candidateHistory, pendingPred, actual);
                    pendingPred = null;
                    if (settledRounds >= maxRounds) {
                        break;
                    }
                }

                // 2) NEW PRED cho ván kế tiếp, dùng lịch sử hiện tại
                pendingPred = callLLMForPick(candidateHistory);
                System.out.printf("[%s] NEW SESS | hist=%s | pred_next=%s | nextStake=%.2f%n",
                        nowStr(), candidateHistory, pendingPred, bankroll.getCurrentStake());

                prevHistory = candidateHistory;
                lastChangeAtMs = System.currentTimeMillis();
                continue;
            }

            // Không đổi round → heartbeat / stall warn
            heartbeat();
        }
        }
        printSummary();
    }

    // ======= SETTLE & REPORT =======
    private void settleAndReport(String history, Pred pred, Actual actual) {
        if (pred == null) {
            pred = Pred.SKIP; // phòng hờ
        }
        // Cập nhật confusion matrix + đếm đúng/sai theo dự đoán
        switch (pred) {
            case TAI:
                if (actual == Actual.T) {
                    pTAI_aT++;
                    correctOnBets++;
                } else {
                    pTAI_aX++;
                }
                break;

            case XIU:
                if (actual == Actual.X) {
                    pXIU_aX++;
                    correctOnBets++;
                } else {
                    pXIU_aT++;
                }
                break;

            case SKIP:
                if (actual == Actual.T) {
                    pSKIP_aT++;
                } else {
                    pSKIP_aX++;
                }
                break;
        } // <-- ĐÃ đóng switch đúng chỗ

        // Bankroll: SKIP = không cược
        bankroll.onRound(
                (pred == Pred.SKIP) ? Bankroll.Pred.SKIP
                        : (pred == Pred.TAI ? Bankroll.Pred.TAI : Bankroll.Pred.XIU),
                (actual == Actual.T) ? Bankroll.Actual.T : Bankroll.Actual.X
        );

        int roundsBet = bankroll.getRoundsBet();
        double accBets = (roundsBet == 0) ? 0.0 : (double) correctOnBets / roundsBet;
        boolean win = (pred == Pred.TAI && actual == Actual.T) || (pred == Pred.XIU && actual == Actual.X);
        String result = (pred == Pred.SKIP) ? "SKIP" : (win ? "WIN" : "LOSE");

        System.out.printf("[%s] Round=%d | pred=%s actual=%s result=%s | acc=%.2f%% | pnl=%.2f | nextStake=%.2f%n",
                nowStr(), settledRounds, pred, actual, result, accBets * 100.0,
                bankroll.getProfit(), bankroll.getCurrentStake());
    }

    private void printSummary() {
        int roundsBet = bankroll.getRoundsBet();
        double accBets = (roundsBet == 0) ? 0.0 : (double) correctOnBets / roundsBet * 100.0;
        System.out.println("\n===== SUMMARY =====");
        System.out.printf("Rounds settled: %d%n", settledRounds);
        System.out.printf("Bets placed: %d%n", roundsBet);
        System.out.printf("Accuracy on bets: %.2f%%%n", accBets);
        System.out.println(bankroll.summary());
        System.out.printf("Confusion: TAI->T:%d TAI->X:%d | XIU->X:%d XIU->T:%d | SKIP->T:%d SKIP->X:%d%n",
                pTAI_aT, pTAI_aX, pXIU_aX, pXIU_aT, pSKIP_aT, pSKIP_aX);
        System.out.printf("Heuristic Fallback: Used=%d Correct=%d (Accuracy=%.2f%%)%n",
                heuristicFallbackUsedCount, heuristicFallbackCorrectCount,
                (heuristicFallbackUsedCount == 0) ? 0.0
                        : (double) heuristicFallbackCorrectCount / heuristicFallbackUsedCount * 100.0);
    }

    // ======= LLM CALL (TIMEOUT + PARSER CỨNG + CHỐNG SKIP) =======
    private Pred callLLMForPick(String history) {
        String inputJson = "{\"history\":\"" + history + "\"}";
        String raw = safeCallLLM(inputJson);
        Pred pred = parsePick(extractJsonObject(raw));

        if (pred == Pred.SKIP && enableRetryOnSkip) {
            // Retry 1 lần với “hint” (không đổi API): thêm nhãn RETRY để tách ngữ cảnh
            String raw2 = safeCallLLM(inputJson + " RETRY");
            pred = parsePick(extractJsonObject(raw2));
        }
        if (pred == Pred.SKIP && enableHeuristicFallback) {
            Pred h = heuristicFallback(history);
            System.out.println("[DBG ] heuristic fallback -> " + h);
            pred = h;
            heuristicFallbackUsedCount++;
        }
        return pred;
    }

    // Timeout cứng: 12s. Không để vòng lặp bị kẹt.
    private String safeCallLLM(String json) {
        try {
            return CompletableFuture
                    .supplyAsync(() -> llm.getAnswer(json))
                    .get(12, TimeUnit.SECONDS);
        } catch (Throwable t) {
            System.err.println("[ERR ] LLM timeout/error -> SKIP: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return "{\"pick\":\"SKIP\"}";
        }
    }

    // Trích phần JSON {...} đầu tiên; nếu không có thì trả raw.trim()
    private String extractJsonObject(String s) {
        if (s == null) {
            return "{\"pick\":\"SKIP\"}";
        }
        int l = s.indexOf('{'), r = s.lastIndexOf('}');
        if (l >= 0 && r > l) {
            return s.substring(l, r + 1);
        }
        return s.trim();
    }

    // Parser: JSON -> "pick", fallback keyword
    private Pred parsePick(String responseJson) {
        if (responseJson == null) {
            return Pred.SKIP;
        }

        // Try to parse as a full JSON object first
        try {
            // Using a simple JSON parser (not robust for all cases, but better than string matching)
            // This assumes the responseJson is a valid JSON string
            // For a more robust solution, a proper JSON library should be used.
            if (responseJson.contains("\"pick\"")) {
                Matcher m = PICK_RE.matcher(responseJson);
                if (m.find()) {
                    return toPred(m.group(1));
                }
            }
        } catch (Exception e) {
            // Fallback to string matching if JSON parsing fails
            System.err.println("[WARN] JSON parsing failed, falling back to string matching: " + e.getMessage());
        }

        // Fallback: tìm khóa "pick":"..."
        int k = responseJson.toLowerCase().indexOf("\"pick\"");
        if (k >= 0) {
            int q1 = responseJson.indexOf('"', k + 6);
            int q2 = (q1 >= 0) ? responseJson.indexOf('"', q1 + 1) : -1;
            if (q2 > q1) {
                return toPred(responseJson.substring(q1 + 1, q2));
            }
        }

        // Siêu fallback: quét từ khoá
        String up = responseJson.toUpperCase();
        if (up.contains("TAI")) {
            return Pred.TAI;
        }
        if (up.contains("XIU")) {
            return Pred.XIU;
        }
        if (up.contains("SKIP")) {
            return Pred.SKIP;
        }
        return Pred.SKIP;
    }

    private Pred toPred(String x) {
        String t = (x == null) ? "" : x.trim().toUpperCase();
        return switch (t) {
            case "TAI" ->
                Pred.TAI;
            case "XIU" ->
                Pred.XIU;
            default ->
                Pred.SKIP;
        };
    }

    // Heuristic optional: majority 13; tie -> anti-run theo last char
    private Pred heuristicFallback(String history) {
        // Simple heuristic: if the last two results are the same, predict the opposite (anti-run)
        // Otherwise, predict the same as the last result (follow)
        if (history.length() >= 2) {
            char last = history.charAt(history.length() - 1);
            char secondLast = history.charAt(history.length() - 2);
            if (last == secondLast) {
                return (last == 'T') ? Pred.XIU : Pred.TAI; // Anti-run
            } else {
                return (last == 'T') ? Pred.TAI : Pred.XIU; // Follow
            }
        }
        // If history is too short, default to TAI (or any other default)
        return Pred.TAI;
    }

    // ======= HEARTBEAT / STALL =======
    private void heartbeat() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatAtMs >= heartbeatMs) {
            lastHeartbeatAtMs = now;
            System.out.println("[HB  ] alive; waiting round close...");
        }
        if (lastChangeAtMs > 0 && now - lastChangeAtMs >= stallWarnMs) {
            System.out.println("[WARN] >90s no round close detected. Check scanning region/scale/visibility.");
            lastChangeAtMs = now; // tránh spam
        }
    }

    // ======= UTILS =======
    private String nowStr() {
        return LocalDateTime.now().format(tsFmt);
    }

    private void log(String s) {
        System.out.println(s);
    }

    // ======= ENUMS =======
    private enum Pred {
        TAI, XIU, SKIP
    }

    private enum Actual {
        T, X
    }

    // ======= BANKROLL (MARTINGALE) =======
    public static class Bankroll {

        private final double baseBet, multiplier;
        private final Double stakeCap;
        private double stake, profit, maxStake, peakProfit, maxDrawdown;
        private int roundsBet, losingStreak, longestLosingStreak, resets;

        public Bankroll(double baseBet, double multiplier, Double stakeCap) {
            if (baseBet <= 0 || multiplier <= 1.0) {
                throw new IllegalArgumentException("baseBet>0 & multiplier>1.0");
            }
            this.baseBet = baseBet;
            this.multiplier = multiplier;
            this.stakeCap = stakeCap;
            this.stake = baseBet;
        }

        public enum Pred {
            TAI, XIU, SKIP
        }

        public enum Actual {
            T, X
        }

        public void onRound(Pred prediction, Actual actual) {
            if (prediction == Pred.SKIP) {
                return; // không đặt thì không ghi nhận
            }
            roundsBet++;
            boolean win = (prediction == Pred.TAI && actual == Actual.T)
                    || (prediction == Pred.XIU && actual == Actual.X);
            if (win) {
                profit += stake;
                losingStreak = 0;
                stake = baseBet;
            } else {
                profit -= stake;
                losingStreak++;
                longestLosingStreak = Math.max(longestLosingStreak, losingStreak);
                stake *= multiplier;
                if (stakeCap != null && stake > stakeCap) {
                    stake = baseBet;
                    resets++;
                }
            }
            if (profit > peakProfit) {
                peakProfit = profit;
            }
            maxDrawdown = Math.max(maxDrawdown, peakProfit - profit);
            maxStake = Math.max(maxStake, stake);
        }

        public int getRoundsBet() {
            return roundsBet;
        }

        public double getCurrentStake() {
            return stake;
        }

        public double getROI() {
            return (roundsBet == 0) ? 0.0 : (profit / (roundsBet * baseBet));
        }

        public double getProfit() {
            return profit;
        }

        public double requiredCapitalByLongestL() {
            int L = Math.max(1, longestLosingStreak);
            return (multiplier == 1.0) ? baseBet * L
                    : baseBet * (Math.pow(multiplier, L) - 1.0) / (multiplier - 1.0);
        }

        public String summary() {
            return String.format(
                    "PnL=%.2f | ROI=%.2f%% | roundsBet=%d | longestL=%d | maxStake=%.2f | MDD=%.2f | resets=%d | nextStake=%.2f | requiredCapital(LLS)=%.2f",
                    profit, getROI() * 100.0, roundsBet, longestLosingStreak, maxStake, maxDrawdown, resets, stake, requiredCapitalByLongestL()
            );
        }
    }
}
