package controller;

import java.awt.Point;
import model.LLM;

public class StatisticDummy {

    Point first = new Point(408, 591);
    Point last = new Point(880, 590);
    DotScannerService scan = new DotScannerService(first, last, 1.0);

    public double winRate(int roundWin, int round) {
        return roundWin / round;
    }

    public void run() {
        int round = -2;
        int roundWin = 0;

        scan.start();
        LLM aiPredict = new LLM();

        Runtime.getRuntime().addShutdownHook(new Thread(scan::stop));

        try {
            while (true) {

                char latest = scan.getLatestResult();
                //System.out.println("History: " + history);
                String history = "";
                String historyPrev = "";
                
                System.out.println("Looping right now...");
                while (historyPrev.equalsIgnoreCase(history)) {
                    history = scan.getLastHistory();
                    if (history == null) {
                        Thread.sleep(300);
                        continue;
                    }
                    historyPrev = history;
                }

                String aiPredictString = aiPredict.getAnswer(history);
                char aiPredictResult = aiPredictString.charAt(21);
                round++;

                System.out.println(latest);
                System.out.println(aiPredictResult);

                if (latest == aiPredictResult) {
                    roundWin++;
                }

                if (round == 10) {
                    System.out.println(winRate(roundWin, round));
                    System.exit(0);
                }

                Thread.sleep(5200);
            }
        } catch (InterruptedException e) {
            System.out.println("Interrupted");
        } finally {
            scan.stop();
        }

    }

}
