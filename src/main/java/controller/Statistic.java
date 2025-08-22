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

    // ===== GIỮ NGUYÊN TỌA ĐỘ CỦA BẠN =====
    private final Point first = new Point(408, 591);
    private final Point last = new Point(880, 590);
    private final double screenScale = 1.0;

    // Poll mỗi 2s
    private final long pollIntervalMs = 2000;

    // Bankroll (gấp thếp)
    private final double baseBet = 3.0;
    private final double multiplier = 2.0;
    private final Double stakeCap = null; // null = no cap

    // Runtime
    private final DotScannerService scan = new DotScannerService(first, last, screenScale);
    private final LLM llm = new LLM();
    private final Bankroll bankroll = new Bankroll(baseBet, multiplier, stakeCap);

    private Character lastResolvedChar = null; // kết quả của ván đã đóng gần nhất
    private Pred pendingPred = null;           // kèo đã “đặt” cho ván đang chạy, chờ settle

    private int settledRounds = 0;   // số ván đã settle (đã có actual)
    private int correctOnBets = 0;   // số đúng trong các ván CÓ đặt (bỏ qua SKIP)

    // confusion
    private int pTAI_aT = 0, pTAI_aX = 0;
    private int pXIU_aX = 0, pXIU_aT = 0;
    private int pSKIP_aT = 0, pSKIP_aX = 0;

    private final DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern PICK_RE = Pattern.compile("\"pick\"\\s*:\\s*\"\\s*(TAI|XIU|SKIP)\\s*\"", Pattern.CASE_INSENSITIVE);

    // Heartbeat & stall
    private long lastHeartbeatAtMs = 0L;
    private final long heartbeatMs = 10_000;
    private long lastChangeAtMs = 0L;
    private final long stallWarnMs = 90_000;

    public void run() {
        scan.start();
        log("[INFO] Statistic started. Poll=" + pollIntervalMs + "ms");

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

            char lastChar = history.charAt(history.length() - 1);

            // VÁN ĐÓNG khi lastChar đổi so với lastResolvedChar
            if (lastResolvedChar == null || lastChar != lastResolvedChar) {
                // 1) SETTLE kèo cũ nếu có
                if (lastResolvedChar != null && pendingPred != null) {
                    Actual actual = (lastChar == 'T') ? Actual.T : Actual.X; // actual của ván vừa đóng
                    settledRounds++;
                    settleAndReport(history, pendingPred, actual); // cập nhật bankroll + confusion + log
                    pendingPred = null; // kèo đã được settle
                }

                // 2) Lấy kèo mới cho ván kế tiếp (dùng history hiện tại sau khi đã có actual mới)
                Pred nextPred = callLLMForPick(history);
                pendingPred = nextPred;

                System.out.printf("[%s] NEW PRED | hist=%s | pred_next=%s | nextStake=%.2f%n",
                        nowStr(), history, pendingPred, bankroll.getCurrentStake());

                lastResolvedChar = lastChar;
                lastChangeAtMs = System.currentTimeMillis();
                continue;
            }

            // Không có ván mới → heartbeat / stall warning
            heartbeat();
        }
    }

    private void settleAndReport(String history, Pred pred, Actual actual) {
        // confusion + correct
        switch (pred) {
            case TAI -> {
                if (actual == Actual.T) {
                    pTAI_aT++;
                    correctOnBets++;
                } else {
                    pTAI_aX++;
                }
            }
            case XIU -> {
                if (actual == Actual.X) {
                    pXIU_aX++;
                    correctOnBets++;
                } else {
                    pXIU_aT++;
                }
            }
            case SKIP -> {
                if (actual == Actual.T) {
                    pSKIP_aT++;
                } else {
                    pSKIP_aX++;
                }
            }
        }

        // bankroll (SKIP = không cược)
        bankroll.onRound(
                (pred == Pred.SKIP) ? Bankroll.Pred.SKIP
                        : (pred == Pred.TAI ? Bankroll.Pred.TAI : Bankroll.Pred.XIU),
                (actual == Actual.T) ? Bankroll.Actual.T : Bankroll.Actual.X
        );

        int roundsBet = bankroll.getRoundsBet();
        double accBets = (roundsBet == 0) ? 0.0 : (double) correctOnBets / roundsBet;

        System.out.printf("[%s] SETTLE  | hist=%s | pred_prev=%s actual=%s | settled=%d bet=%d acc(bet)=%.2f%% | %s%n",
                nowStr(), history, pred, actual, settledRounds, roundsBet, accBets * 100.0, bankroll.summary());

        if (settledRounds % 10 == 0) {
            System.out.printf("Confusion: TAI->T:%d TAI->X:%d | XIU->X:%d XIU->T:%d | SKIP->T:%d SKIP->X:%d%n",
                    pTAI_aT, pTAI_aX, pXIU_aX, pXIU_aT, pSKIP_aT, pSKIP_aX);
        }
    }

    private Pred callLLMForPick(String history) {
        String inputJson = "{\"history\":\"" + history + "\"}";
        System.out.println("[DBG ] calling LLM for next pick...");
        String raw = safeCallLLM(inputJson);           // có timeout cứng
        String jsonOnly = extractJsonObject(raw);
        Pred pred = parsePick(jsonOnly);
        System.out.println("[DBG ] LLM returned pick=" + pred);
        return pred;
    }

    // Timeout cứng 12s để không bao giờ treo vòng lặp
    private String safeCallLLM(String json) {
        try {
            return CompletableFuture
                    .supplyAsync(() -> llm.getAnswer(json))
                    .get(12, TimeUnit.SECONDS);
        } catch (Throwable t) {
            System.err.println("[ERR ] LLM timeout/error -> SKIP: " + t.getMessage());
            return "{\"pick\":\"SKIP\"}";
        }
    }

    // Heartbeat + stall warning
    private void heartbeat() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatAtMs >= heartbeatMs) {
            lastHeartbeatAtMs = now;
            System.out.println("[HB  ] alive; waiting round close...");
        }
        if (lastChangeAtMs > 0 && now - lastChangeAtMs >= stallWarnMs) {
            System.out.println("[WARN] >90s không thấy round close. Kiểm tra tọa độ/scale/khu vực hiển thị.");
            lastChangeAtMs = now; // tránh spam cảnh báo
        }
    }

    // ===== JSON helpers =====
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

    private Pred parsePick(String responseJson) {
        if (responseJson == null) {
            return Pred.SKIP;
        }
        Matcher m = PICK_RE.matcher(responseJson);
        if (m.find()) {
            String pick = m.group(1).toUpperCase();
            return switch (pick) {
                case "TAI" ->
                    Pred.TAI;
                case "XIU" ->
                    Pred.XIU;
                default ->
                    Pred.SKIP;
            };
        }
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

    private String nowStr() {
        return LocalDateTime.now().format(tsFmt);
    }

    private void log(String s) {
        System.out.println(s);
    }

    // ===== Enums =====
    private enum Pred {
        TAI, XIU, SKIP
    }

    private enum Actual {
        T, X
    }

    // ===== Bankroll (nhúng gọn, y như trước) =====
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
                return;
            }
            roundsBet++;
            boolean win = (prediction == Pred.TAI && actual == Actual.T) || (prediction == Pred.XIU && actual == Actual.X);
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

        public double getCurrentStake() {
            return stake;
        }

        public int getRoundsBet() {
            return roundsBet;
        }

        public double getROI() {
            return (roundsBet == 0) ? 0.0 : (profit / (roundsBet * baseBet));
        }

        public String summary() {
            return String.format("PnL=%.2f | ROI=%.2f%% | roundsBet=%d | longestL=%d | maxStake=%.2f | MDD=%.2f | resets=%d | nextStake=%.2f | requiredCapital(LLS)=%.2f",
                    profit, getROI() * 100.0, roundsBet, longestLosingStreak, maxStake, maxDrawdown, resets, stake, requiredCapitalByLongestL());
        }

        public double requiredCapitalByLongestL() {
            int L = Math.max(1, longestLosingStreak);
            return (multiplier == 1.0) ? baseBet * L : baseBet * (Math.pow(multiplier, L) - 1.0) / (multiplier - 1.0);
        }
    }
}
