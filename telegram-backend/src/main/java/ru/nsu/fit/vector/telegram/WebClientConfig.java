package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${server.api.url}")
    private String serverUrl;

    @Bean
    public WebClient imageServerClient() {
        return WebClient.builder()
                .baseUrl(serverUrl)
                .build();
    }
}