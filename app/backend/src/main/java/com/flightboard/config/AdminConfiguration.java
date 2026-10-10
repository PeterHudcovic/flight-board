package com.flightboard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightboard.admin.AdminServer;
import com.flightboard.persistence.MongoFetchStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AdminConfiguration {
    @Bean
    public AdminServer adminServer(MongoFetchStore store, ObjectMapper mapper, FlightBoardProperties properties) {
        return new AdminServer(store, mapper, properties);
    }
}
