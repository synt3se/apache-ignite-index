package ru.nsu.fit.sberlab.vectorindex.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gateway (слой публичного API).
 * Принимает картинку/текст от клиента, оркестрирует CLIP + Index.
 * Сам никаких векторных вычислений не делает.
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
