package controller;

public class Bankroll {
    // Config
    private final double baseBet;     // ví dụ: 3.0
    private final double multiplier;  // ví dụ: 2.0 (x2, x3 thì 3.0)
    private final Double stakeCap;    // có thể null nếu không cap

    // State
    private double stake;             // mức cược hiện tại
    private double profit;            // lãi/lỗ tích luỹ
    private int roundsBet;            // số ván đã đặt (không tính SKIP)
    private int losingStreak;         // chuỗi thua hiện tại
    private int longestLosingStreak;  // chuỗi thua dài nhất
    private double maxStake;          // stake lớn nhất chạm tới
    private int resets;               // số lần reset do vượt cap (nếu có)

    // Risk metrics
    private double peakProfit;        // đỉnh PnL từng đạt
    private double maxDrawdown;       // max(peak - currentProfit)

    public Bankroll(double baseBet, double multiplier, Double stakeCap) {
        if (baseBet <= 0 || multiplier <= 1.0) {
            throw new IllegalArgumentException("baseBet>0 và multiplier>1.0");
        }
        this.baseBet = baseBet;
        this.multiplier = multiplier;
        this.stakeCap = stakeCap;
        this.stake = baseBet;
        this.profit = 0.0;
        this.peakProfit = 0.0;
        this.maxDrawdown = 0.0;
    }

    public enum Pred { TAI, XIU, SKIP }
    public enum Actual { T, X }

    // Gọi mỗi ván khi đã có actual & prediction
    public void onRound(Pred prediction, Actual actual) {
        if (prediction == Pred.SKIP) {
            // không cược
            return;
        }

        roundsBet++;
        boolean win = (prediction == Pred.TAI && actual == Actual.T)
                   || (prediction == Pred.XIU && actual == Actual.X);

        if (win) {
            profit += stake;
            losingStreak = 0;
            stake = baseBet; // reset stake
        } else {
            profit -= stake;
            losingStreak++;
            longestLosingStreak = Math.max(longestLosingStreak, losingStreak);
            stake = stake * multiplier;

            // Nếu có cap, giới hạn stake
            if (stakeCap != null && stake > stakeCap) {
                stake = baseBet;
                resets++;
            }
        }

        // risk tracking
        if (profit > peakProfit) peakProfit = profit;
        double dd = peakProfit - profit;
        if (dd > maxDrawdown) maxDrawdown = dd;

        if (stake > maxStake) maxStake = stake;
    }

    // ===== GETTERS / REPORT =====
    public double getProfit() { return profit; }
    public int getRoundsBet() { return roundsBet; }
    public double getROI() {
        // ROI theo tổng vốn đã “risked”: xấp xỉ roundsBet * baseBet
        return (roundsBet == 0) ? 0.0 : (profit / (roundsBet * baseBet));
    }
    public int getLongestLosingStreak() { return longestLosingStreak; }
    public double getMaxStake() { return maxStake; }
    public double getMaxDrawdown() { return maxDrawdown; }
    public int getResets() { return resets; }
    public double getCurrentStake() { return stake; }

    // vốn tối thiểu cần có để chịu được L thua liên tiếp (không cap)
    public double requiredCapitalByLongestL() {
        int L = Math.max(1, longestLosingStreak);
        if (multiplier == 1.0) return baseBet * L;
        return baseBet * (Math.pow(multiplier, L) - 1.0) / (multiplier - 1.0);
    }

    // Snapshot text cho log
    public String summary() {
        return String.format(
            "PnL=%.2f | ROI=%.2f%% | roundsBet=%d | longestL=%d | maxStake=%.2f | MDD=%.2f | resets=%d | nextStake=%.2f | requiredCapital(LLS)=%.2f",
            profit, getROI()*100.0, roundsBet, longestLosingStreak, maxStake, maxDrawdown, resets, stake, requiredCapitalByLongestL()
        );
    }
}
