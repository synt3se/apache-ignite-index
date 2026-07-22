package ru.nsu.fit.sberlab.vectorindex.gateway.config;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Logs every outbound HTTP call the gateway makes (to the CLIP and index services).
 *
 * The interceptor is registered through a RestClientCustomizer, so it applies to any
 * RestClient built from the auto-configured RestClient.Builder (ClipClient, IndexClient).
 */
@Configuration
public class LoggingConfig {

    private static final Logger log = LoggerFactory.getLogger("gateway.outbound");

    @Bean
    public RestClientCustomizer outboundLoggingCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            long startMs = System.currentTimeMillis();
            try {
                ClientHttpResponse response = execution.execute(request, body);
                long elapsedMs = System.currentTimeMillis() - startMs;
                log.info("Outbound {} {} responded {} in {} ms",
                        request.getMethod(), request.getURI(), response.getStatusCode(), elapsedMs);
                return response;
            } catch (IOException | RuntimeException e) {
                long elapsedMs = System.currentTimeMillis() - startMs;
                log.warn("Outbound {} {} failed in {} ms: {}",
                        request.getMethod(), request.getURI(), elapsedMs, e.toString());
                throw e;
            }
        });
    }
}
