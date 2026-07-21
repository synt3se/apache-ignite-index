package ru.nsu.fit.vectorserver.benchmark;


import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import ru.nsu.fit.vectorserver.VectorServerApplication;
import ru.nsu.fit.vectorserver.VectorService;
import ru.nsu.fit.vectorserver.benchmark.dataset.BenchmarkDatasetRunner;

/*
======================== BEFORE RUNNING BENCHMARK =======================
- restart database
- set vector.dimension in application.properties to tasting db dimension
- set path to tasting db file in the variable hdf5Path
- set tasting db neighborCount in the variable neighborCount
 */

@SpringBootApplication
public class BenchmarkMain {

    private enum Mode{ANN_BENCHMARK_TEST, OUR_DATASET, N_CLIENTS}

    private static final String DATABASE_PATH = env("DATABASE_PATH", "C:/Programming/Java_Projects/apache-ignite-mvp/apache-ignite-index/data/d260k.csv");
    private static final String QUERIES_PATH = env("QUERIES_PATH", "C:/Programming/Java_Projects/apache-ignite-mvp/apache-ignite-index/data/quieries.csv");
    private static final String RESULTS_PATH = env("RESULTS_PATH", "C:/Programming/Java_Projects/apache-ignite-mvp/apache-ignite-index/data/r260k.csv");
    private static final int NEIGHBOR_COUNT = 10;



    private static final boolean LOAD_DATABASE = true;
    private static final Mode BENCHMARK_MODE = Mode.OUR_DATASET;
    private static final BenchmarkDatasetRunner.IndexType INDEX_MODE =
            BenchmarkDatasetRunner.IndexType.JVECTOR;


    public static void main(String[] args){
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(VectorServerApplication.class)
                        .web(WebApplicationType.NONE).run(args);


        if (BENCHMARK_MODE == Mode.ANN_BENCHMARK_TEST){
            try{
                VectorService vectorService = context.getBean(VectorService.class);
                BenchmarkTestRunner runner = new BenchmarkTestRunner(vectorService);
                int neighborCount = 10;
                String hdf5Path = "index-vector-server/src/main/resources/coco-i2i-512-angular.hdf5";
                //String hdf5Path = "index-vector-server/src/main/resources/mnist-784-euclidean.hdf5";
                runner.run(neighborCount, hdf5Path);

            }catch (IllegalArgumentException e){
                System.err.println("[ERROR ILLEGAL ARGUMENT]: " + e.getMessage());
            }
            finally {
                context.close();
            }
        }else if (BENCHMARK_MODE == Mode.N_CLIENTS){
            context.close();
        }else if (BENCHMARK_MODE == Mode.OUR_DATASET){
            try{
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
            }catch (IllegalArgumentException e){
                System.err.println("[ERROR ILLEGAL ARGUMENT]: " + e.getMessage());
            }
            finally {
                context.close();
            }
        }

    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v : def;
    }
}