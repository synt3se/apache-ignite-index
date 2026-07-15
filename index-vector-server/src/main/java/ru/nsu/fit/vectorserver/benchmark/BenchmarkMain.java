package ru.nsu.fit.vectorserver.benchmark;


import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import ru.nsu.fit.vectorserver.VectorServerApplication;
import ru.nsu.fit.vectorserver.VectorService;

/*
======================== BEFORE RUNNING BENCHMARK =======================
- restart database
- set vector.dimension in application.properties to tasting db dimension
- set path to tasting db file in the variable hdf5Path
- set tasting db neighborCount in the variable neighborCount
 */

@SpringBootApplication
public class BenchmarkMain {

    private enum Mode{
        ANN_BENCHMARK_TEST,
        OUR_DATASET,
        N_CLIENTS
    }

    public static void main(String[] args){
        System.out.println("Hello");
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(VectorServerApplication.class)
                        .web(WebApplicationType.NONE)
                        .run(args);

        Mode mode = Mode.ANN_BENCHMARK_TEST;

        if (mode == Mode.ANN_BENCHMARK_TEST){
            try{
                VectorService vectorService = context.getBean(VectorService.class);
                BenchmarkTestRunner runner = new BenchmarkTestRunner(vectorService);
                int neighborCount = 10;
                String hdf5Path = "index-vector-server/src/main/resources/coco-i2i-512-angular.hdf5";
                //String hdf5Path = "index-vector-server/src/main/resources/mnist-784-euclidean.hdf5";
                runner.run(neighborCount, hdf5Path);

            }catch (IllegalArgumentException e){
                System.err.println();
            }
            finally {
                context.close();
            }
        }else if (mode == Mode.N_CLIENTS){
            context.close();
        }else if (mode == Mode.OUR_DATASET){
            try{
                VectorService vectorService = context.getBean(VectorService.class);
                BenchmarkTestRunner runner = new BenchmarkTestRunner(vectorService);
                int neighborCount = 10;
                String hdf5Path = "index-vector-server/src/main/resources/coco-i2i-512-angular.hdf5";
                //String hdf5Path = "index-vector-server/src/main/resources/mnist-784-euclidean.hdf5";
                runner.run(neighborCount, hdf5Path);

            }catch (IllegalArgumentException e){
                System.err.println();
            }
            finally {
                context.close();
            }
        }

    }
}