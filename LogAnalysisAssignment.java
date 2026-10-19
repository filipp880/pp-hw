/*
  Запуск в IntelliJ.
  Открыть Run, потом Edit Configurations.
  В поле Program arguments вставить одну из команд:

  check
  generate logs 20 1000
  analyze logs 4 2 per-path

  check проверяет программу.
  generate создаёт 20 файлов по 1000 строк.
  analyze читает логи 4 потоками и открывает не больше 2 файлов сразу.
*/
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LogAnalysisAssignment {
    public record LogEntry(String method, String path, int status,
                           long responseBytes, long durationMillis) {}

    public record LogFile(Path path) {}

    public record PathStatistics(long requests, long successful, long errors,
                                 long responseBytes, long totalDurationMillis,
                                 long maxDurationMillis) {}

    public record Report(long totalRequests, long successfulRequests,
                         long errorRequests, long responseBytes,
                         long maxDurationMillis) {}

    public static final class LogFileLines {
        private LogFileLines() {}

        public interface LineIterator extends Iterator<String>, AutoCloseable {
            void close();
        }

        public static LineIterator iterator(Path path) {
            try {
                BufferedReader reader = Files.newBufferedReader(
                        path, StandardCharsets.UTF_8);
                return new LineIterator() {
                    private String nextLine = readNext();
                    private boolean closed;

                    private String readNext() {
                        try {
                            String line = reader.readLine();
                            if (line == null) close();
                            return line;
                        } catch (IOException e) {
                            close();
                            throw new RuntimeException("Cannot read " + path, e);
                        }
                    }

                    public void close() {
                        if (closed) return;
                        closed = true;
                        try {
                            reader.close();
                        } catch (IOException e) {
                            throw new RuntimeException("Cannot close " + path, e);
                        }
                    }

                    public boolean hasNext() {
                        return nextLine != null;
                    }

                    public String next() {
                        if (nextLine == null) throw new NoSuchElementException();
                        String result = nextLine;
                        nextLine = readNext();
                        return result;
                    }
                };
            } catch (IOException e) {
                throw new RuntimeException("Cannot open " + path, e);
            }
        }
    }

    public static final class GenerateLogs {
        private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE"};
        private static final String[] PATHS = {"/", "/index.html", "/login",
                "/api/users", "/api/orders", "/api/products", "/search", "/health"};
        private static final int[] STATUSES = {200, 200, 200, 201, 204,
                301, 400, 401, 404, 500};

        private GenerateLogs() {}

        public static void generate(Path dir, int files, int lines) throws IOException {
            writeLogs(dir, files, lines);
        }

        private static void writeLogs(Path dir, int files, int lines) throws IOException {
            if (files < 0 || lines < 0)
                throw new IllegalArgumentException("Counts must be non-negative");
            Files.createDirectories(dir);
            Random random = new Random(42);
            for (int fileId = 0; fileId < files; fileId++) {
                Path file = dir.resolve("access-%03d.log".formatted(fileId));
                try (BufferedWriter writer = Files.newBufferedWriter(
                        file, StandardCharsets.UTF_8)) {
                    for (int line = 0; line < lines; line++) {
                        String method = METHODS[random.nextInt(METHODS.length)];
                        String path = PATHS[random.nextInt(PATHS.length)];
                        int status = STATUSES[random.nextInt(STATUSES.length)];
                        long bytes = 100L + random.nextInt(100_000);
                        long duration = 1L + random.nextInt(500);
                        writer.write("%s %s %d %d %d%n".formatted(
                                method, path, status, bytes, duration));
                    }
                }
            }
        }
    }

    public static final class MalformedLogLineException
            extends IllegalArgumentException {
        public MalformedLogLineException(String message) {
            super(message);
        }
    }

    public static LogEntry parseLine(String line) {
        if (line == null) throw new MalformedLogLineException("Line is null");
        String[] fields = line.trim().split("\\s+");
        if (fields.length != 5)
            throw new MalformedLogLineException(
                    "Expected 5 fields, got " + fields.length + ": " + line);
        if (fields[0].isEmpty() || fields[1].isEmpty())
            throw new MalformedLogLineException("Empty method or path: " + line);
        int status = parseInt(fields[2], "status", line);
        if (status < 200 || status > 599)
            throw new MalformedLogLineException("Bad status: " + line);
        long bytes = parseLong(fields[3], "responseBytes", line);
        long duration = parseLong(fields[4], "durationMillis", line);
        if (bytes < 0 || duration < 0)
            throw new MalformedLogLineException("Negative value: " + line);
        return new LogEntry(fields[0], fields[1], status, bytes, duration);
    }

    private static int parseInt(String value, String field, String line) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new MalformedLogLineException("Bad " + field + ": " + line);
        }
    }

    private static long parseLong(String value, String field, String line) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new MalformedLogLineException("Bad " + field + ": " + line);
        }
    }

    private static final class MutablePathStatistics {
        private long requests;
        private long successful;
        private long errors;
        private long responseBytes;
        private long totalDurationMillis;
        private long maxDurationMillis;

        synchronized void add(LogEntry entry) {
            requests++;
            if (entry.status() < 400) successful++;
            else errors++;
            responseBytes += entry.responseBytes();
            totalDurationMillis += entry.durationMillis();
            if (entry.durationMillis() > maxDurationMillis)
                maxDurationMillis = entry.durationMillis();
        }

        synchronized PathStatistics snapshot() {
            return new PathStatistics(requests, successful, errors,
                    responseBytes, totalDurationMillis, maxDurationMillis);
        }
    }

    public interface StatisticsAccumulator {
        void add(LogEntry entry);

        Map<String, PathStatistics> snapshot();
    }

    public static final class GlobalStatisticsAccumulator
            implements StatisticsAccumulator {
        private final Map<String, MutablePathStatistics> byPath = new HashMap<>();

        public synchronized void add(LogEntry entry) {
            MutablePathStatistics stats = byPath.get(entry.path());
            if (stats == null) {
                stats = new MutablePathStatistics();
                byPath.put(entry.path(), stats);
            }
            stats.add(entry);
        }

        public synchronized Map<String, PathStatistics> snapshot() {
            Map<String, PathStatistics> result = new TreeMap<>();
            for (Map.Entry<String, MutablePathStatistics> entry : byPath.entrySet())
                result.put(entry.getKey(), entry.getValue().snapshot());
            return Collections.unmodifiableMap(result);
        }
    }

    public static final class PerPathStatisticsAccumulator
            implements StatisticsAccumulator {
        private final ConcurrentHashMap<String, MutablePathStatistics> byPath =
                new ConcurrentHashMap<>();

        public void add(LogEntry entry) {
            MutablePathStatistics stats = byPath.get(entry.path());
            if (stats == null) {
                byPath.putIfAbsent(entry.path(), new MutablePathStatistics());
                stats = byPath.get(entry.path());
            }
            stats.add(entry);
        }

        public Map<String, PathStatistics> snapshot() {
            Map<String, PathStatistics> result = new TreeMap<>();
            for (Map.Entry<String, MutablePathStatistics> entry : byPath.entrySet())
                result.put(entry.getKey(), entry.getValue().snapshot());
            return Collections.unmodifiableMap(result);
        }
    }

    private static final class Counters {
        final AtomicLong filesProcessed = new AtomicLong();
        final AtomicLong filesFailed = new AtomicLong();
        final AtomicLong filesIncomplete = new AtomicLong();
        final AtomicLong linesRead = new AtomicLong();
        final AtomicLong validRecords = new AtomicLong();
        final AtomicLong malformedRecords = new AtomicLong();
        final AtomicLong readErrors = new AtomicLong();
        final AtomicInteger activeFiles = new AtomicInteger();
        final AtomicInteger maxActiveFiles = new AtomicInteger();
        final List<String> failedFiles =
                Collections.synchronizedList(new ArrayList<String>());

        void beginFile() {
            int active = activeFiles.incrementAndGet();
            int observed;
            do {
                observed = maxActiveFiles.get();
                if (active <= observed) return;
            } while (!maxActiveFiles.compareAndSet(observed, active));
        }

        void endFile() {
            activeFiles.decrementAndGet();
        }
    }

    public static final class StopFlag {
        private volatile boolean stopped;

        public void requestStop() {
            stopped = true;
        }

        public boolean isStopRequested() {
            return stopped;
        }
    }

    public record CountersSnapshot(long filesDiscovered, long filesProcessed,
                                   long filesFailed, long filesIncomplete,
                                   long linesRead, long validRecords,
                                   long malformedRecords, long readErrors,
                                   int maxActiveFiles, int activeAfterFinish) {}

    public record AnalysisReport(Report totals,
                                 Map<String, PathStatistics> perPath,
                                 CountersSnapshot counters,
                                 boolean stoppedEarly,
                                 List<String> failedFiles) {}

    public static List<LogFile> discoverLogFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) throw new IOException("Not a directory: " + dir);
        List<LogFile> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.log")) {
            for (Path path : stream)
                if (Files.isRegularFile(path)) files.add(new LogFile(path));
        }
        Collections.sort(files, (a, b) ->
                a.path().toString().compareTo(b.path().toString()));
        return files;
    }

    public static AnalysisReport analyzeSequentially(Path dir) throws IOException {
        List<LogFile> files = discoverLogFiles(dir);
        Counters counters = new Counters();
        StatisticsAccumulator accumulator = new PerPathStatisticsAccumulator();
        for (LogFile file : files) {
            counters.beginFile();
            boolean completed = false;
            try (LogFileLines.LineIterator iterator =
                         LogFileLines.iterator(file.path())) {
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    counters.linesRead.incrementAndGet();
                    try {
                        accumulator.add(parseLine(line));
                        counters.validRecords.incrementAndGet();
                    } catch (MalformedLogLineException e) {
                        counters.malformedRecords.incrementAndGet();
                    }
                }
                completed = true;
            } catch (RuntimeException e) {
                counters.readErrors.incrementAndGet();
                counters.failedFiles.add(file.path() + ": " + e.getMessage());
            } finally {
                counters.endFile();
            }
            if (completed) counters.filesProcessed.incrementAndGet();
            else counters.filesFailed.incrementAndGet();
        }
        return buildReport(files.size(), counters, accumulator, 1, false);
    }

    public static AnalysisReport analyzeInParallel(Path dir, int workers,
                                                   int permits,
                                                   boolean perPathLocks)
            throws IOException, InterruptedException {
        return analyzeInParallel(dir, workers, permits, perPathLocks, new StopFlag());
    }

    public static AnalysisReport analyzeInParallel(Path dir, int workers,
                                                   int permits,
                                                   boolean perPathLocks,
                                                   StopFlag stopFlag)
            throws IOException, InterruptedException {
        if (workers < 1 || permits < 1 || stopFlag == null)
            throw new IllegalArgumentException("Bad parallel parameters");
        List<LogFile> files = discoverLogFiles(dir);
        Counters counters = new Counters();
        StatisticsAccumulator accumulator = perPathLocks
                ? new PerPathStatisticsAccumulator()
                : new GlobalStatisticsAccumulator();
        Semaphore semaphore = new Semaphore(permits);
        CountDownLatch completion = new CountDownLatch(files.size());
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            for (LogFile file : files) {
                final Path path = file.path();
                pool.execute(new Runnable() {
                    public void run() {
                        processFile(path, semaphore, completion,
                                counters, accumulator, stopFlag);
                    }
                });
            }
            completion.await();
        } finally {
            shutdownAndAwait(pool);
        }
        return buildReport(files.size(), counters, accumulator,
                permits, stopFlag.isStopRequested());
    }

    private static void processFile(Path file, Semaphore semaphore,
                                    CountDownLatch completion,
                                    Counters counters,
                                    StatisticsAccumulator accumulator,
                                    StopFlag stopFlag) {
        boolean acquired = false;
        boolean entered = false;
        boolean completed = false;
        try {
            semaphore.acquire();
            acquired = true;
            counters.beginFile();
            entered = true;
            try (LogFileLines.LineIterator iterator =
                         LogFileLines.iterator(file)) {
                while (iterator.hasNext()) {
                    if (stopFlag.isStopRequested()) break;
                    String line = iterator.next();
                    counters.linesRead.incrementAndGet();
                    try {
                        accumulator.add(parseLine(line));
                        counters.validRecords.incrementAndGet();
                    } catch (MalformedLogLineException e) {
                        counters.malformedRecords.incrementAndGet();
                    }
                }
                completed = !iterator.hasNext() && !stopFlag.isStopRequested();
            } catch (RuntimeException e) {
                counters.readErrors.incrementAndGet();
                counters.failedFiles.add(file + ": " + e.getMessage());
            } finally {
                if (entered) counters.endFile();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            counters.failedFiles.add(file + ": interrupted");
        } finally {
            if (acquired) semaphore.release();
            if (completed) counters.filesProcessed.incrementAndGet();
            else if (stopFlag.isStopRequested())
                counters.filesIncomplete.incrementAndGet();
            else counters.filesFailed.incrementAndGet();
            completion.countDown();
        }
    }

    private static void shutdownAndAwait(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(1, TimeUnit.MINUTES)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static AnalysisReport buildReport(int fileCount, Counters counters,
                                              StatisticsAccumulator accumulator,
                                              int permits,
                                              boolean stoppedEarly) {
        Map<String, PathStatistics> perPath = accumulator.snapshot();
        long requests = 0;
        long successful = 0;
        long errors = 0;
        long bytes = 0;
        long maxDuration = 0;
        for (PathStatistics stats : perPath.values()) {
            if (stats.successful() + stats.errors() != stats.requests())
                throw new AssertionError("requests != successful + errors");
            if (stats.maxDurationMillis() > stats.totalDurationMillis())
                throw new AssertionError("maxDuration > totalDuration");
            requests += stats.requests();
            successful += stats.successful();
            errors += stats.errors();
            bytes += stats.responseBytes();
            if (stats.maxDurationMillis() > maxDuration)
                maxDuration = stats.maxDurationMillis();
        }
        CountersSnapshot snapshot = new CountersSnapshot(
                fileCount,
                counters.filesProcessed.get(),
                counters.filesFailed.get(),
                counters.filesIncomplete.get(),
                counters.linesRead.get(),
                counters.validRecords.get(),
                counters.malformedRecords.get(),
                counters.readErrors.get(),
                counters.maxActiveFiles.get(),
                counters.activeFiles.get());
        if (snapshot.validRecords() != requests)
            throw new AssertionError("validRecords != totalRequests");
        if (snapshot.filesDiscovered() != snapshot.filesProcessed()
                + snapshot.filesFailed() + snapshot.filesIncomplete())
            throw new AssertionError("file counters do not add up");
        if (snapshot.activeAfterFinish() != 0)
            throw new AssertionError("activeFiles != 0 after finish");
        if (snapshot.maxActiveFiles() > permits)
            throw new AssertionError("maxActiveFiles > permits");
        return new AnalysisReport(
                new Report(requests, successful, errors, bytes, maxDuration),
                perPath, snapshot, stoppedEarly,
                Collections.unmodifiableList(
                        new ArrayList<String>(counters.failedFiles)));
    }

    public static void printReport(AnalysisReport report) {
        Report totals = report.totals();
        CountersSnapshot c = report.counters();
        System.out.println("=== LOG ANALYSIS REPORT ===");
        if (report.stoppedEarly()) System.out.println("PARTIAL REPORT");
        System.out.println("Files: discovered=" + c.filesDiscovered()
                + ", processed=" + c.filesProcessed()
                + ", failed=" + c.filesFailed()
                + ", incomplete=" + c.filesIncomplete());
        System.out.println("Lines: read=" + c.linesRead()
                + ", valid=" + c.validRecords()
                + ", malformed=" + c.malformedRecords()
                + ", readErrors=" + c.readErrors());
        System.out.println("Requests: total=" + totals.totalRequests()
                + ", successful=" + totals.successfulRequests()
                + ", errors=" + totals.errorRequests());
        System.out.println("Bytes=" + totals.responseBytes()
                + ", maxDuration=" + totals.maxDurationMillis()
                + ", maxActive=" + c.maxActiveFiles()
                + ", activeAfterFinish=" + c.activeAfterFinish());
        System.out.println("Per URL:");
        List<Map.Entry<String, PathStatistics>> entries =
                new ArrayList<>(report.perPath().entrySet());
        Collections.sort(entries, new Comparator<
                Map.Entry<String, PathStatistics>>() {
            public int compare(Map.Entry<String, PathStatistics> a,
                               Map.Entry<String, PathStatistics> b) {
                int result = Long.compare(b.getValue().requests(),
                        a.getValue().requests());
                return result != 0 ? result : a.getKey().compareTo(b.getKey());
            }
        });
        for (Map.Entry<String, PathStatistics> entry : entries) {
            PathStatistics s = entry.getValue();
            System.out.println("  " + entry.getKey()
                    + " requests=" + s.requests()
                    + " successful=" + s.successful()
                    + " errors=" + s.errors()
                    + " bytes=" + s.responseBytes()
                    + " totalMs=" + s.totalDurationMillis()
                    + " maxMs=" + s.maxDurationMillis());
        }
        if (!report.failedFiles().isEmpty()) {
            System.out.println("Failed files:");
            for (String failed : report.failedFiles())
                System.out.println("  " + failed);
        }
    }

    private static void deleteRecursively(Path root) {
        File file = root.toFile();
        File[] children = file.listFiles();
        if (children != null)
            for (File child : children) deleteRecursively(child.toPath());
        file.delete();
    }

    private static void selfCheck() throws Exception {
        Path dir = Files.createTempDirectory("log-assignment-");
        GenerateLogs.generate(dir, 3, 7);
        int files = 0;
        int lines = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                files++;
                Iterator<String> iterator = LogFileLines.iterator(path);
                while (iterator.hasNext()) {
                    String[] fields = iterator.next().trim().split("\\s+");
                    if (fields.length != 5) throw new AssertionError("Bad line");
                    Integer.parseInt(fields[2]);
                    Long.parseLong(fields[3]);
                    Long.parseLong(fields[4]);
                    lines++;
                }
            }
        }
        if (files != 3 || lines != 21)
            throw new AssertionError("Bad generator output");
        deleteRecursively(dir);

        LogEntry entry = parseLine("GET /api/users 200 820 4");
        if (entry.status() != 200 || entry.responseBytes() != 820)
            throw new AssertionError("Bad parse");
        String[] badLines = {"GET / 200 1", "GET / bad 1 1",
                "GET / 200 bad 1", "GET / 199 1 1", "GET / 200 -1 1"};
        for (String line : badLines) {
            boolean thrown = false;
            try {
                parseLine(line);
            } catch (MalformedLogLineException e) {
                thrown = true;
            }
            if (!thrown) throw new AssertionError("Expected bad line");
        }

        Path checkDir = Files.createTempDirectory("log-check-");
        try {
            GenerateLogs.generate(checkDir, 2, 100);
            Files.write(checkDir.resolve("access-000.log"),
                    "BROKEN LINE\n".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
            Files.write(checkDir.resolve("broken.log"),
                    new byte[]{(byte) 0xC3, 0x28, 10});
            AnalysisReport sequential = analyzeSequentially(checkDir);
            AnalysisReport parallel = analyzeInParallel(checkDir, 4, 2, true);
            AnalysisReport parallelGlobal = analyzeInParallel(checkDir, 4, 2, false);
            if (sequential.totals().totalRequests() != 200
                    || sequential.counters().malformedRecords() != 1
                    || sequential.counters().readErrors() != 1
                    || sequential.counters().filesProcessed() != 2
                    || sequential.counters().filesFailed() != 1)
                throw new AssertionError("Bad sequential counters");
            if (!sequential.totals().equals(parallel.totals())
                    || !sequential.perPath().equals(parallel.perPath()))
                throw new AssertionError("Sequential and per-path parallel differ");
            if (!sequential.totals().equals(parallelGlobal.totals())
                    || !sequential.perPath().equals(parallelGlobal.perPath()))
                throw new AssertionError("Sequential and global parallel differ");
            Path stopDir = Files.createTempDirectory("log-stop-");
            try {
                GenerateLogs.generate(stopDir, 1, 10_000);
                StopFlag stopFlag = new StopFlag();
                stopFlag.requestStop();
                AnalysisReport stopped =
                        analyzeInParallel(stopDir, 4, 2, true, stopFlag);
                if (!stopped.stoppedEarly()
                        || stopped.totals().totalRequests() != 0
                        || stopped.counters().filesIncomplete() != 1
                        || stopped.counters().activeAfterFinish() != 0)
                    throw new AssertionError("Bad early stop");
            } finally {
                deleteRecursively(stopDir);
            }
        } finally {
            deleteRecursively(checkDir);
        }
        System.out.println("OK: checks passed");
    }

    private static void analyzeCommand(String[] args) throws Exception {
        Path dir = Path.of(args.length > 1 ? args[1] : "logs");
        int workers = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        int permits = args.length > 3 ? Integer.parseInt(args[3]) : workers;
        boolean perPath = args.length <= 4 || !args[4].equalsIgnoreCase("global");
        long stopAfter = args.length > 5 ? Long.parseLong(args[5]) : -1;
        StopFlag stopFlag = new StopFlag();
        Thread stopper = null;
        if (stopAfter > 0) {
            stopper = new Thread(() -> {
                try {
                    Thread.sleep(stopAfter);
                } catch (InterruptedException e) {
                    return;
                }
                stopFlag.requestStop();
            });
            stopper.start();
        }
        long start = System.nanoTime();
        AnalysisReport report = analyzeInParallel(
                dir, workers, permits, perPath, stopFlag);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        if (stopper != null) stopper.interrupt();
        printReport(report);
        System.out.println("Elapsed: " + elapsed + " ms");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            selfCheck();
            System.out.println("Usage: java LogAnalysisAssignment "
                    + "generate <dir> <files> <lines> | check | "
                    + "analyze <dir> [workers] [permits] [per-path|global] [stopMs]");
            return;
        }
        if (args[0].equals("generate")) {
            Path dir = Path.of(args.length > 1 ? args[1] : "logs");
            int files = args.length > 2 ? Integer.parseInt(args[2]) : 20;
            int lines = args.length > 3 ? Integer.parseInt(args[3]) : 1_000;
            GenerateLogs.generate(dir, files, lines);
            System.out.println("Generated " + files + " files in " + dir);
            return;
        }
        if (args[0].equals("check")) {
            selfCheck();
            return;
        }
        if (args[0].equals("analyze")) {
            analyzeCommand(args);
            return;
        }
        System.out.println("Usage: java LogAnalysisAssignment "
                + "generate <dir> <files> <lines> | check | "
                + "analyze <dir> [workers] [permits] [per-path|global] [stopMs]");
    }
}
