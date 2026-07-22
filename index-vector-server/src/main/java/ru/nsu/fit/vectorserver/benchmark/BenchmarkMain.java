package ru.nsu.fit.vectorserver.benchmark;


import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import ru.nsu.fit.vectorserver.VectorServerApplication;
import ru.nsu.fit.vectorserver.VectorService;
import ru.nsu.fit.vectorserver.benchmark.dataset.BenchmarkDatasetRunner;
import ru.nsu.fit.vectorserver.benchmark.highload.BenchmarkHighLoadRunner;
import ru.nsu.fit.vectorserver.benchmark.recall.BenchmarkTestRunner;

/*
======================== BEFORE RUNNING BENCHMARK =======================
- restart database
- set vector.dimension in application.properties to tasting db dimension
- set path to tasting db file in the variable hdf5Path
- set tasting db neighborCount in the variable neighborCount
 */

@SpringBootApplication
public class BenchmarkMain {

    private enum Mode {
        ANN_BENCHMARK_TEST, OUR_DATASET,
        HIGH_LOAD, N_CLIENTS
    }

    private static final String DATABASE_PATH = "C:/Users/danil/Desktop/IgniteDB/database.csv";
    private static final String QUERIES_PATH = "C:/Users/danil/Desktop/IgniteDB/quieries.csv";
    private static final String RESULTS_PATH = "C:/Users/danil/Desktop/IgniteDB/results.csv";
    private static final int NEIGHBOR_COUNT = 10;


    private static final boolean LOAD_DATABASE = true;
    private static final Mode BENCHMARK_MODE = Mode.OUR_DATASET;
    private static final BenchmarkDatasetRunner.IndexType INDEX_MODE =
            BenchmarkDatasetRunner.IndexType.JVECTOR;


    private static final int HIGHLOAD_MAX_IN_FLIGHT = 64;
    private static final int HIGHLOAD_TARGET_RPS = 100;
    private static final int HIGHLOAD_WARMUP_SECONDS = 10;
    private static final int HIGHLOAD_TEST_SECONDS = 60;

    public static void main(String[] args) {

        try (ConfigurableApplicationContext context =
                     new SpringApplicationBuilder(VectorServerApplication.class)
                             .web(WebApplicationType.NONE).run(args))
        {
            switch (BENCHMARK_MODE){
                case ANN_BENCHMARK_TEST -> {
                    String hdf5Path = "index-vector-server/src/main/resources/coco-i2i-512-angular.hdf5";
                    VectorService vectorService = context.getBean(VectorService.class);
                    BenchmarkTestRunner runner = new BenchmarkTestRunner(vectorService);
                    runner.run(NEIGHBOR_COUNT, hdf5Path);
                }

                case N_CLIENTS -> {

                }

                case OUR_DATASET -> {
                    VectorService service = context.getBean(VectorService.class);
                    BenchmarkDatasetRunner runner =
                            new BenchmarkDatasetRunner(service, INDEX_MODE);

                    runner.run(
                            NEIGHBOR_COUNT,
                            DATABASE_PATH,
                            QUERIES_PATH,
                            RESULTS_PATH,
                            LOAD_DATABASE
                    );
                }

                case HIGH_LOAD -> {
                    VectorService service = context.getBean(VectorService.class);
                    BenchmarkHighLoadRunner runner = new BenchmarkHighLoadRunner(service);
                    runner.run(
                            HIGHLOAD_MAX_IN_FLIGHT,
                            HIGHLOAD_TARGET_RPS,
                            HIGHLOAD_WARMUP_SECONDS,
                            HIGHLOAD_TEST_SECONDS,
                            NEIGHBOR_COUNT,
                            QUERIES_PATH
                    );
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("[ERROR ILLEGAL ARGUMENT]: " + e.getMessage());
        }catch (Exception e){
            System.err.println("[UNKNOWN ERROR]: " + e.getMessage());
            e.printStackTrace();
        }
    }
}