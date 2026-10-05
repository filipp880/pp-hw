import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/*
ДЗ 2. Ответы на вопросы про взаимную блокировку.

1  Если существует полный порядок ресурсов, и потоки блокируют ресурсы строго
   по этому порядку, а освобождают в обратном порядке, то взаимная блокировка
   невозможна.

   ОТвет: для взаимной блокировки нужен цикл ожидания. Например, поток 1
   держит ресурс A и ждёт ресурс B, а поток 2 держит ресурс B и ждёт ресурс A.
   Но если все берут ресурсы по одному общему порядку, такой цикл не может
   получиться: никто не сможет ждать более ранний ресурс, удерживая более поздний.

2  Если освобождать ресурсы в том же порядке, в каком они брались, то при
   строгом запрете который действует на вюс программу ситуация та же: взаимной блокировки нет.
   Порядок освобождения сам по себе не создаёт цикл ожидания.
   Но если освобождение в том же порядке позволяет
   потоку отпустить младший ресурс, продолжая держать старший, а потом снова
   потребовать этот младший ресурс, тогда взаимная блокировка возможна.
*/

public class DoubleCheckedLockingExercise {

    private static final class ExpensiveResource {
        private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

        private int[] data;
        private long checksum;

        private ExpensiveResource() {
            CONSTRUCTIONS.incrementAndGet();

            data = new int[10_000];

            long calculatedChecksum = 0;
            for (int i = 0; i < data.length; i++) {
                data[i] = i * 31 + 17;
                calculatedChecksum += data[i];
            }

            checksum = calculatedChecksum;
        }

        boolean isValid() {
            if (data == null || data.length != 10_000) {
                return false;
            }

            long calculatedChecksum = 0;
            for (int value : data) {
                calculatedChecksum += value;
            }

            return calculatedChecksum == checksum;
        }
    }

    private static final class LazyResource {
        private final Object monitor = new Object();
        private volatile ExpensiveResource instance;

        ExpensiveResource getInstance() {
            if (instance == null) {
                synchronized (monitor) {
                    if (instance == null) {
                        instance = new ExpensiveResource();
                    }
                }
            }

            return instance;
        }
    }

    public static void main(String[] args) throws Exception {
        int rounds = 100;
        int callersPerRound = 32;

        ExecutorService pool = Executors.newFixedThreadPool(16);

        ExpensiveResource.CONSTRUCTIONS.set(0);

        try {
            for (int round = 0; round < rounds; round++) {
                LazyResource lazy = new LazyResource();

                Set<ExpensiveResource> instances = new HashSet<>();

                @SuppressWarnings("unchecked")
                Future<ExpensiveResource>[] results = new Future[callersPerRound];

                for (int caller = 0; caller < callersPerRound; caller++) {
                    results[caller] = pool.submit(lazy::getInstance);
                }

                for (Future<ExpensiveResource> result : results) {
                    ExpensiveResource instance;

                    try {
                        instance = result.get(5, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        throw new AssertionError("The DCL test did not finish", e);
                    }

                    if (!instance.isValid()) {
                        throw new AssertionError(
                                "A thread observed a partially initialized resource"
                        );
                    }

                    instances.add(instance);
                }

                if (instances.size() != 1) {
                    throw new AssertionError(
                            "Round " + round + " created " + instances.size()
                                    + " different instances"
                    );
                }
            }
        } finally {
            pool.shutdownNow();
        }

        int constructions = ExpensiveResource.CONSTRUCTIONS.get();

        if (constructions != rounds) {
            throw new AssertionError(
                    "Expected " + rounds + " constructor calls, got " + constructions
            );
        }

        System.out.println("OK: one fully initialized instance per lazy holder");
    }
}