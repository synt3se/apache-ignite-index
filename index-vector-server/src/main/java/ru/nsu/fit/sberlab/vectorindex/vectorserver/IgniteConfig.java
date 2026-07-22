package ru.nsu.fit.sberlab.vectorindex.vectorserver;

import org.apache.ignite.Ignition;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.configuration.ClientConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;

@Configuration
public class IgniteConfig {
    private IgniteClient igniteClient;

    @Bean
    public IgniteClient igniteClient(@Value("${ignite.address}") String igniteAddress) {
        ClientConfiguration configuration = new ClientConfiguration()
                .setAddresses(igniteAddress.split("\\s*,\\s*"));   // список адресов = failover

        igniteClient = Ignition.startClient(configuration);
        return igniteClient;
    }

    @PreDestroy
    public void closeIgniteClient() {
        if (igniteClient != null) {
            igniteClient.close();
        }
    }
}