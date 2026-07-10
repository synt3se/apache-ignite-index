package ru.nsu.fit.vector.telegram.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${gateway.api.url}")
    private String gatewayUrl;

    @Bean
    public WebClient imageServerClient() {
        return WebClient.builder()
                .baseUrl(gatewayUrl)
                .build();
    }
}