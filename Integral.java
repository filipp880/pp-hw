import java.util.Locale;

public class Integral {
    public static final double A = 0.0;
    public static final double B = Math.PI;
    public static final int INTERVALS = 100_000_000;
    public static final int THREADS = 6;

    public static double f(double x) {
        double u = 3.0 * x;
        return Math.exp(-x)
                + Math.pow(Math.abs(Math.sin(u + 0.1)), 1.7)
                * Math.log1p(Math.abs(Math.cos(2.0 * x)) + 1.0)
                + Math.cos(u) / 4.0;
    }

    public static double integrateSequential() {
        double step = (B - A) / INTERVALS;
        double sum = 0.0;
        for (int i = 0; i < INTERVALS; i++) {
            sum += f(A + (i + 0.5) * step);
        }
        return sum * step;
    }

    public static Thread taskThread(int n, int[] boundaries, double[] results) {
        return new Thread(() -> {
            var start = boundaries[n];
            var finish = boundaries[n + 1];
            double step = (B - A) / INTERVALS;
            var acc = 0.0;
            for (int i = start; i < finish; i++) {
                acc += f(A + (i + 0.5) * step);
            }
            results[n] = acc;
        });
    }

    public static double integrateParallel() throws InterruptedException {
        var boundaries = new int[THREADS + 1];
        for (int i = 0; i <= THREADS; i++) {
            boundaries[i] = (int) ((long) i * INTERVALS / THREADS);
        }

        var threads = new Thread[THREADS];
        var results = new double[THREADS];

        for (int i = 0; i < THREADS; i++) {
            threads[i] = taskThread(i, boundaries, results);
        }

        for (var t : threads) {
            t.start();
        }
        for (var t : threads) {
            t.join();
        }

        var sum = 0.0;
        for (var r : results) {
            sum += r;
        }
        return sum * (B - A) / INTERVALS;
    }

    public static void main(String[] args) throws InterruptedException {
        integrateSequential();
        integrateParallel();

        var start = System.nanoTime();
        var seq = integrateSequential();
        var seqTime = System.nanoTime() - start;

        var pStart = System.nanoTime();
        var par = integrateParallel();
        var parTime = System.nanoTime() - pStart;

        System.out.printf(Locale.ROOT, "Sequential result:  %.12f%n", seq);
        System.out.printf(Locale.ROOT, "Parallel result:    %.12f%n", par);
        System.out.printf(Locale.ROOT, "Difference:         %.3e%n", Math.abs(seq - par));
        System.out.printf(Locale.ROOT, "Sequential time (ms): %.3f%n", seqTime / 1e6);
        System.out.printf(Locale.ROOT, "Parallel time (ms):   %.3f%n", parTime / 1e6);
        System.out.printf(Locale.ROOT, "Speedup: %.2fx%n", (double) seqTime / parTime);
    }
}