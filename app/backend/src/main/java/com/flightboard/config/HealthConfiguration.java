package com.flightboard.config;

import com.flightboard.persistence.MongoFetchStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HealthConfiguration {
    @Bean(name = "mongoHealthIndicator")
    public HealthIndicator mongoHealthIndicator(MongoFetchStore store) {
        return () -> store.ready() ? Health.up().build() : Health.down().build();
    }
}
