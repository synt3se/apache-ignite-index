package ru.nsu.fit.vectorserver.benchmark;


import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import ru.nsu.fit.vectorserver.VectorServerApplication;
import ru.nsu.fit.vectorserver.VectorService;
import ru.nsu.fit.vectorserver.benchmark.dataset.BenchmarkDatasetRunner;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

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

    private static final String DATABASE_PATH = "C:/Users/Виталий Дьяченко/Desktop/ignite/d260k.csv";
    private static final String QUERIES_PATH = "C:/Users/Виталий Дьяченко/Desktop/ignite/benchmark/quieries.csv";
    private static final String RESULTS_PATH = "C:/Users/Виталий Дьяченко/Desktop/ignite/benchmark/r260k.csv";
    private static final int NEIGHBOR_COUNT = 10;

    private static final String DOCKER_CONTAINER_PATTERN = "ignite-node-%d";
    private static final int NODE_COUNT = 2;
    private static final int DB_STARTUP_WAIT_SECONDS = 10; // Время ожидания базы после перезапуска

    private static final boolean LOAD_DATABASE = true;
    private static final Mode BENCHMARK_MODE = Mode.OUR_DATASET;
    private static final BenchmarkDatasetRunner.IndexType INDEX_MODE =
            BenchmarkDatasetRunner.IndexType.JVECTOR;


    public static void main(String[] args){
        restartDockerContainers(NODE_COUNT);

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

    private static void restartDockerContainers(int totalNodes) {
        System.out.println("=== [DOCKER] Total nodes to restart: " + totalNodes + " ===");

        boolean atLeastOneRestarted = false;

        for (int i = 1; i <= totalNodes; i++) {
            String containerName = String.format(DOCKER_CONTAINER_PATTERN, i);
            boolean success = runDockerRestartCommand(containerName);
            if (success) {
                atLeastOneRestarted = true;
            }
        }

        if (atLeastOneRestarted) {
            System.out.println("=== [DOCKER] Waiting " + DB_STARTUP_WAIT_SECONDS + "s for Ignite cluster to initialize and form topology... ===");
            try {
                TimeUnit.SECONDS.sleep(DB_STARTUP_WAIT_SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[DOCKER WARNING] Warmup sleep was interrupted!");
            }
            System.out.println("=== [DOCKER] Resume startup. ===");
        } else {
            System.err.println("=== [DOCKER WARNING] No containers were restarted. Proceeding immediately. ===");
        }
    }

    private static boolean runDockerRestartCommand(String containerName) {
        System.out.println("=== [DOCKER] Attempting to restart container: " + containerName + " ===");
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/c", "docker restart " + containerName);
            } else {
                pb = new ProcessBuilder("docker", "restart", containerName);
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[DOCKER OUT] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("=== [DOCKER] Container " + containerName + " restarted successfully! ===");
                return true;
            } else {
                System.err.println("=== [DOCKER ERROR] Failed to restart " + containerName + ". Exit code: " + exitCode + " ===");
                return false;
            }
        } catch (Exception e) {
            System.err.println("=== [DOCKER ERROR] Critical exception during restart of " + containerName + ": " + e.getMessage() + " ===");
            return false;
        }
    }
}