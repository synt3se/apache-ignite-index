package ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.VectorServerApplication;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.VectorService;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.clients.BenchmarkNClientsRunner;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.dataset.BenchmarkDatasetRunner;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.highload.BenchmarkHighLoadRunner;
import ru.nsu.fit.sberlab.vectorindex.vectorserver.benchmark.recall.BenchmarkAnnRunner;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@SpringBootApplication
public class BenchmarkMain {
    public static void main(String[] args) {
        try (ConfigurableApplicationContext context =
                     new SpringApplicationBuilder(VectorServerApplication.class)
                             .web(WebApplicationType.NONE).run(args))
        {
            printConfiguration();

            VectorService vectorService = context.getBean(VectorService.class);

            long preparationNanos = 0L;

            if (LOAD_DATABASE && BENCHMARK_MODE != Mode.ANN_BENCHMARK_TEST) {
                preparationNanos = new DatabaseLoader(vectorService).load(DATABASE_PATH);
            }

            switch (BENCHMARK_MODE){
                case ANN_BENCHMARK_TEST -> {
                    String HDF5_PATH = env("HDF5_PATH", "/data/coco-i2i-512-angular.hdf5");
                    BenchmarkAnnRunner runner = new BenchmarkAnnRunner(vectorService);
                    runner.run(NEIGHBOR_COUNT, HDF5_PATH);
                }

                case N_CLIENTS -> {
                    Environment environment = context.getEnvironment();
                    String igniteAddress = environment.getRequiredProperty("ignite.address");
                    String cacheName = environment.getProperty(
                            "ignite.cache.name",
                            "vectors");
                    int dimension = Integer.parseInt(environment.
                            getRequiredProperty("vector.dimension"));
                    BenchmarkNClientsRunner runner = new BenchmarkNClientsRunner();

                    runner.run(
                            N_CLIENTS_COUNT,
                            N_CLIENTS_WARMUP_SECONDS,
                            N_CLIENTS_TEST_SECONDS,
                            NEIGHBOR_COUNT,
                            QUERIES_PATH,
                            igniteAddress,
                            cacheName,
                            dimension,
                            preparationNanos
                    );
                }

                case OUR_DATASET -> {
                    BenchmarkDatasetRunner runner =
                            new BenchmarkDatasetRunner(vectorService, INDEX_MODE);
                    boolean isPrintMismatch = Boolean.parseBoolean(
                            env("PRINT_MISMATCH", "false")
                    );

                    String measurementsPath =
                            INDEX_MODE == BenchmarkDatasetRunner.IndexType.JVECTOR ?
                                    "/data/results/dataset-jvector.csv" :
                                    "/data/results/dataset-brute-force.csv";
                    runner.run(
                            NEIGHBOR_COUNT,
                            QUERIES_PATH,
                            RESULTS_PATH,
                            isPrintMismatch,
                            preparationNanos,
                            measurementsPath
                    );
                }

                case HIGH_LOAD -> {
                    BenchmarkHighLoadRunner runner = new BenchmarkHighLoadRunner(vectorService);

                    QueryReader queryReader = new QueryReader();
                    List<QueryReader.QueryVector> queries = queryReader.read(QUERIES_PATH);
                    runner.run(
                            HIGHLOAD_MAX_IN_FLIGHT,
                            HIGHLOAD_TARGET_RPS,
                            HIGHLOAD_WARMUP_SECONDS,
                            HIGHLOAD_TEST_SECONDS,
                            NEIGHBOR_COUNT,
                            QUERIES_PATH,
                            preparationNanos,
                            queries,
                            RESULTS_DIR
                    );
                }
                case HIGH_LOAD_SWEEP -> {
                    validateHighLoadSweepArguments();

                    System.out.println("Sweep start RPS: " + HIGHLOAD_SWEEP_START_RPS);
                    System.out.println("Sweep max RPS: " + HIGHLOAD_SWEEP_MAX_RPS);
                    System.out.println("Sweep RPS step: " + HIGHLOAD_SWEEP_RPS_STEP);
                    System.out.println("Warmup per point: " + HIGHLOAD_WARMUP_SECONDS + " s");
                    System.out.println("Test duration per point: " + HIGHLOAD_TEST_SECONDS + " s");
                    System.out.println("Pause between points: " + HIGHLOAD_SWEEP_PAUSE_SECONDS + " s");
                    System.out.println("Sweep order: " + (HIGHLOAD_SWEEP_DESCENDING ? "DESCENDING" : "ASCENDING"));

                    BenchmarkHighLoadRunner runner = new BenchmarkHighLoadRunner(vectorService);

                    QueryReader queryReader = new QueryReader();
                    List<QueryReader.QueryVector> queries = queryReader.read(QUERIES_PATH);

                    PrintStream originalOut = System.out;
                    System.setOut(new PrintStream(new OutputStream() {
                        @Override
                        public void write(int b) {
                            // прогрев: вывод не нужен
                        }
                    }));
                    runner.run(
                            HIGHLOAD_MAX_IN_FLIGHT, 300, 10, 30,
                            NEIGHBOR_COUNT, QUERIES_PATH, preparationNanos, queries, null
                    );
                    System.setOut(originalOut);

                    System.out.println("Stabilization before sweep: 15 s");
                    Thread.sleep(15_000L);

                    List<Integer> points = new ArrayList<>();
                    for (int rps = HIGHLOAD_SWEEP_START_RPS;
                         rps <= HIGHLOAD_SWEEP_MAX_RPS;
                         rps += HIGHLOAD_SWEEP_RPS_STEP) {
                        points.add(rps);
                    }
                    if (HIGHLOAD_SWEEP_DESCENDING) {
                        Collections.reverse(points);
                    }

                    int pointNumber = 1;
                    for (int targetRps : points) {
                        System.out.println();
                        System.out.println("####################################################");
                        System.out.println("HIGHLOAD SWEEP POINT " + pointNumber + " of " + points.size());
                        System.out.println("Target RPS: " + targetRps);
                        System.out.println("####################################################");

                        runner.run(
                                HIGHLOAD_MAX_IN_FLIGHT,
                                targetRps,
                                HIGHLOAD_WARMUP_SECONDS,
                                HIGHLOAD_TEST_SECONDS,
                                NEIGHBOR_COUNT,
                                QUERIES_PATH,
                                preparationNanos,
                                queries,
                                RESULTS_DIR
                        );

                        pointNumber++;

                        if (pointNumber <= points.size() && HIGHLOAD_SWEEP_PAUSE_SECONDS > 0) {
                            System.out.println("Pause before next point: "
                                    + HIGHLOAD_SWEEP_PAUSE_SECONDS + " s");
                            Thread.sleep(HIGHLOAD_SWEEP_PAUSE_SECONDS * 1_000L);
                        }
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[ERROR ILLEGAL ARGUMENT]: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[UNKNOWN EXCEPTION IN BENCHMARK_MAIN]: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void validateHighLoadSweepArguments() {
        if (HIGHLOAD_SWEEP_START_RPS <= 0) {
            throw new IllegalArgumentException(
                    "HIGHLOAD_SWEEP_START_RPS must be positive"
            );
        }

        if (HIGHLOAD_SWEEP_MAX_RPS < HIGHLOAD_SWEEP_START_RPS) {
            throw new IllegalArgumentException(
                    "HIGHLOAD_SWEEP_MAX_RPS must be greater than or equal to "
                            + "HIGHLOAD_SWEEP_START_RPS"
            );
        }

        if (HIGHLOAD_SWEEP_RPS_STEP <= 0) {
            throw new IllegalArgumentException(
                    "HIGHLOAD_SWEEP_RPS_STEP must be positive"
            );
        }

        if (HIGHLOAD_SWEEP_PAUSE_SECONDS < 0) {
            throw new IllegalArgumentException(
                    "HIGHLOAD_SWEEP_PAUSE_SECONDS must not be negative"
            );
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v : def;
    }

    private static BenchmarkDatasetRunner.IndexType readIndexMode() {
        String value = System.getenv().getOrDefault(
                "INDEX_TYPE",
                "JVECTOR_INDEX"
        );

        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "JVECTOR_INDEX" -> BenchmarkDatasetRunner.IndexType.JVECTOR;
            case "BRUTE_FORCE_INDEX" -> BenchmarkDatasetRunner.IndexType.BRUTE_FORCE;
            default -> throw new IllegalArgumentException("Unknown INDEX_TYPE: " + value);
        };
    }
    private static void printConfiguration(){
        System.out.println("\n\n================ BENCHMARK MAIN STARTED =================");
        System.out.println("RUN MODE: " + BENCHMARK_MODE);
        String index = System.getenv().getOrDefault(
                "INDEX_TYPE",
                "(Empty value) AUTO=JVECTOR_INDEX"
        );
        System.out.println("INDEX_TYPE: " + index);
        System.out.println("Load database: " + LOAD_DATABASE);
        System.out.println("Database path: " + DATABASE_PATH);
        System.out.println("Queries path: " + QUERIES_PATH);
        System.out.println("Results path: " + RESULTS_PATH);
        System.out.println("Neighbor count: " + NEIGHBOR_COUNT);
        System.out.println("=======================================================");

    }
    private static final BenchmarkDatasetRunner.IndexType INDEX_MODE = readIndexMode();
    private static final boolean LOAD_DATABASE = Boolean.parseBoolean(env("LOAD_DATABASE", "true"));
    private static final String DATABASE_PATH = env("DATABASE_PATH", "/srv/vindex-data/260k/dataset.csv");
    private static final String QUERIES_PATH = env("QUERIES_PATH", "/srv/vindex-data/260k/quieries.csv");
    private static final String RESULTS_PATH = env("RESULTS_PATH", "/srv/vindex-data/260k/results.csv");
    private static final int NEIGHBOR_COUNT = Integer.parseInt(env("NEIGHBOR_COUNT", "10"));
    private static final int HIGHLOAD_MAX_IN_FLIGHT = Integer.parseInt(env("HIGHLOAD_MAX_IN_FLIGHT", "64"));
    private static final int HIGHLOAD_TARGET_RPS = Integer.parseInt(env("HIGHLOAD_TARGET_RPS", "350"));
    private static final int HIGHLOAD_WARMUP_SECONDS = Integer.parseInt(env("HIGHLOAD_WARMUP_SECONDS", "10"));
    private static final int HIGHLOAD_TEST_SECONDS = Integer.parseInt(env("HIGHLOAD_TEST_SECONDS", "60"));
    private static final int N_CLIENTS_COUNT = Integer.parseInt(env("N_CLIENTS_COUNT", "8"));
    private static final int N_CLIENTS_WARMUP_SECONDS = Integer.parseInt(env("N_CLIENTS_WARMUP_SECONDS", "10"));
    private static final int N_CLIENTS_TEST_SECONDS = Integer.parseInt(env("N_CLIENTS_TEST_SECONDS", "60"));
    private static final int HIGHLOAD_SWEEP_START_RPS =
            Integer.parseInt(env("HIGHLOAD_SWEEP_START_RPS", "100"));

    private static final int HIGHLOAD_SWEEP_MAX_RPS =
            Integer.parseInt(env("HIGHLOAD_SWEEP_MAX_RPS", "1000"));

    private static final int HIGHLOAD_SWEEP_RPS_STEP =
            Integer.parseInt(env("HIGHLOAD_SWEEP_RPS_STEP", "100"));

    private static final int HIGHLOAD_SWEEP_PAUSE_SECONDS =
            Integer.parseInt(env("HIGHLOAD_SWEEP_PAUSE_SECONDS", "0"));

    private static final String RESULTS_DIR = env("RESULTS_DIR", "/data/results");

    private static final boolean HIGHLOAD_SWEEP_DESCENDING =
            Boolean.parseBoolean(env("HIGHLOAD_SWEEP_DESCENDING", "false"));

    private enum Mode {
        ANN_BENCHMARK_TEST,
        OUR_DATASET,
        HIGH_LOAD,
        HIGH_LOAD_SWEEP,
        N_CLIENTS
    }

    private static final Mode BENCHMARK_MODE = Mode.valueOf(
            env("BENCHMARK_MODE", "OUR_DATASET").trim().toUpperCase(Locale.ROOT)
    );

}