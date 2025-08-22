package controller;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import model.LLM;

public class DotScannerService {
    private final int NUM_DOTS = 13;
    private final int PATCH = 8;
    private final int MARGIN_X = 20, MARGIN_Y = 20;
    private final int PERIOD_MS = 5000;

    private Point pFirst, pLast;
    private volatile boolean running = false;
    private volatile String lastHistory = null;

    private double scale = 1.0;

//    public 
    
    public DotScannerService(Point first, Point last, double scale) {
        this.pFirst = first;
        this.pLast = last;
        this.scale = scale;
    }

    public void start() {
        if (running) return;
        running = true;
        new Thread(this::loop).start();
    }

    public void stop() {
        running = false;
    }

    public String getLastHistory() {
        return lastHistory;
    }

    public char getLatestResult() {
        if (lastHistory == null || lastHistory.length() < NUM_DOTS) return '?';
        return lastHistory.charAt(NUM_DOTS - 1);
    }

    // ==================== nội bộ ======================
    private void loop() {
        try {
            Robot robot = new Robot();
            while (running) {
                long t0 = System.currentTimeMillis();

                double dx = (pLast.x - pFirst.x) / (double)(NUM_DOTS - 1);
                int minX = (int)Math.floor(Math.min(pFirst.x, pLast.x) - MARGIN_X - PATCH);
                int maxX = (int)Math.ceil (Math.max(pFirst.x, pLast.x) + MARGIN_X + PATCH);
                int cY   = pFirst.y;
                int minY = cY - (MARGIN_Y + PATCH);
                int maxY = cY + (MARGIN_Y + PATCH);

                Rectangle roi = new Rectangle(minX, minY,
                        Math.max(10, maxX - minX + 1),
                        Math.max(10, maxY - minY + 1));

                BufferedImage img = robot.createScreenCapture(roi);

                double[] bright = new double[NUM_DOTS];
                for (int i = 0; i < NUM_DOTS; i++) {
                    int cx = (int)Math.round(pFirst.x + i * dx) - roi.x;
                    int cy = cY - roi.y;
                    bright[i] = avgBrightness(img, cx, cy, PATCH);
                }

                double thr = kmeans2Threshold(bright);
                StringBuilder sb = new StringBuilder(NUM_DOTS);
                for (double v : bright) sb.append(v < thr ? 'T' : 'X');
                lastHistory = sb.toString();

                long spent = System.currentTimeMillis() - t0;
                long sleep = PERIOD_MS - spent;
                if (sleep > 0) Thread.sleep(sleep);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private double avgBrightness(BufferedImage img, int cx, int cy, int r) {
        int x0 = Math.max(0, cx - r), x1 = Math.min(img.getWidth()-1,  cx + r);
        int y0 = Math.max(0, cy - r), y1 = Math.min(img.getHeight()-1, cy + r);
        long sum=0, cnt=0;
        for (int y=y0; y<=y1; y++)
            for (int x=x0; x<=x1; x++) {
                int rgb = img.getRGB(x,y);
                int R=(rgb>>16)&255, G=(rgb>>8)&255, B=rgb&255;
                int lum = (int)Math.round(0.299*R + 0.587*G + 0.114*B);
                sum += lum; cnt++;
            }
        return (double)sum/Math.max(1,cnt);
    }

    private double kmeans2Threshold(double[] v){
        double[] a = Arrays.copyOf(v, v.length);
        Arrays.sort(a);
        double m1=a[3], m2=a[a.length-4];
        for(int it=0; it<10; it++){
            double s1=0,c1=0,s2=0,c2=0;
            for(double x: v){
                if (Math.abs(x-m1)<=Math.abs(x-m2)) { s1+=x; c1++; }
                else { s2+=x; c2++; }
            }
            double n1=c1>0?s1/c1:m1, n2=c2>0?s2/c2:m2;
            if (Math.abs(n1-m1)+Math.abs(n2-m2)<0.01){ m1=n1; m2=n2; break; }
            m1=n1; m2=n2;
        }
        return (m1+m2)/2.0;
    }
}
