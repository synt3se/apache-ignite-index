package ru.nsu.fit.vectorserver.benchmark;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import ru.nsu.fit.vectorserver.VectorServerApplication;
import ru.nsu.fit.vectorserver.VectorService;
import ru.nsu.fit.vectorserver.core.BruteForceIndex;
import ru.nsu.fit.vectorserver.core.Index;


@SpringBootApplication
public class BenchmarkMain {
    public static void main(String[] args){
        System.out.println("Hello");
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(VectorServerApplication.class)
                        .web(WebApplicationType.NONE)
                        .run(args);
        try{
            VectorService vectorService = context.getBean(VectorService.class);
            BenchmarkRunner runner = new BenchmarkRunner(vectorService);
            int vectorCount = 10;
            int queryCount = 2;
            int dimension = 512;
            int neighborCount = 10;
            runner.run(vectorCount, queryCount, dimension, neighborCount);
        }finally {
            context.close();
        }
    }
}
