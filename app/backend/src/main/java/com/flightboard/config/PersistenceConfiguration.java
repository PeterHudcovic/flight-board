package com.flightboard.config;

import com.flightboard.fetch.FetchCoordinator;
import com.flightboard.fetch.FetchScheduler;
import com.flightboard.persistence.MongoFetchStore;
import com.flightboard.source.FlightSource;
import com.flightboard.validation.BatchValidator;
import com.mongodb.client.MongoClient;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {
    @Bean(initMethod = "initialize")
    public MongoFetchStore fetchStore(MongoClient client, @Value("${spring.data.mongodb.database}") String database,
                                     FlightBoardProperties properties, Clock clock) {
        return new MongoFetchStore(client, database, properties, clock);
    }

    @Bean
    public FetchCoordinator fetchCoordinator(MongoFetchStore store, FlightSource source,
                                             BatchValidator validator, FlightBoardProperties properties) {
        return new FetchCoordinator(store, source, validator::validate, properties);
    }

    @Bean
    public FetchScheduler fetchScheduler(FetchCoordinator coordinator, MongoFetchStore store, FlightBoardProperties properties) {
        return new FetchScheduler(coordinator, store, properties);
    }
}
