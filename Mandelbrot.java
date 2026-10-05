public class Mandelbrot {

    private static final int WIDTH = 80;
    private static final int HEIGHT = 24;
    private static final int THREAD_COUNT = 4;
    private static final int MAX_ITER = 80;

    public static void main(String[] args) throws InterruptedException {
        int[] picture = new int[WIDTH * HEIGHT];

        Thread[] threads = new Thread[THREAD_COUNT];

        int currentRow = 0;
        int rowsPerThread = HEIGHT / THREAD_COUNT;

        for (int i = 0; i < THREAD_COUNT; i++) {
            int startRow = currentRow;
            int endRow;

            if (i == THREAD_COUNT - 1) {
                endRow = HEIGHT;
            } else {
                endRow = startRow + rowsPerThread;
            }

            threads[i] = new MandelbrotThread(picture, startRow, endRow);
            currentRow = endRow;
        }

        long startTime = System.nanoTime();

        for (int i = 0; i < THREAD_COUNT; i++) {
            threads[i].start();
        }

        for (int i = 0; i < THREAD_COUNT; i++) {
            threads[i].join();
        }

        long finishTime = System.nanoTime();

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int value = picture[y * WIDTH + x];

                if (value == 0) {
                    System.out.print('#');
                } else if (value < 16) {
                    System.out.print('*');
                } else {
                    System.out.print('.');
                }
            }

            System.out.println();
        }

        System.out.println("Time in nanoseconds: " + (finishTime - startTime));
    }

    private static class MandelbrotThread extends Thread {

        private final int[] picture;
        private final int startRow;
        private final int endRow;

        MandelbrotThread(int[] picture, int startRow, int endRow) {
            this.picture = picture;
            this.startRow = startRow;
            this.endRow = endRow;
        }

        @Override
        public void run() {
            double minReal = -2.0;
            double maxReal = 1.0;

            double minImage = -1.0;
            double maxImage = 1.0;

            for (int y = startRow; y < endRow; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    double cReal = minReal + (maxReal - minReal) * x / (WIDTH - 1);
                    double cImage = minImage + (maxImage - minImage) * y / (HEIGHT - 1);

                    double zReal = 0.0;
                    double zImage = 0.0;

                    int iteration = 0;

                    while (iteration < MAX_ITER) {
                        double zRealSquared = zReal * zReal;
                        double zImageSquared = zImage * zImage;

                        if (zRealSquared + zImageSquared > 4.0) {
                            break;
                        }

                        double newReal = zRealSquared - zImageSquared + cReal;

                        zImage = 2.0 * zReal * zImage + cImage;
                        zReal = newReal;

                        iteration++;
                    }

                    if (iteration == MAX_ITER) {
                        picture[y * WIDTH + x] = 0;
                    } else {
                        picture[y * WIDTH + x] = iteration;
                    }
                }
            }
        }
    }
}