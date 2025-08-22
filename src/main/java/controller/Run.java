package controller;

import view.Statistic;

public class Run {
    public static void main(String[] args) {
        int rounds = 10;
        if (args.length > 0) {
            try {
                rounds = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid round count, using default 10");
            }
        }
        Statistic st = new Statistic(rounds);
        st.run();
    }
}
