package ru.nsu.fit.sberlab.vectorindex.vectorserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VectorServerApplication {
    public static void main(String[] args){
        SpringApplication.run(VectorServerApplication.class, args);
    }
}
