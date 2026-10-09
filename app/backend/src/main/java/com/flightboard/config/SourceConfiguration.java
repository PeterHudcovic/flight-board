package com.flightboard.config;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.flightboard.source.AeroDataBoxSource;
import com.flightboard.source.FlightSource;
import com.flightboard.source.StubFlightSource;
import com.flightboard.validation.BatchValidator;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SourceConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .addModule(new JavaTimeModule()).build();
    }

    @Bean
    public FlightSource flightSource(FlightBoardProperties properties, ObjectMapper mapper, Clock clock) {
        return switch (properties.source()) {
            case STUB -> new StubFlightSource(properties, mapper, clock);
            case AERODATABOX -> new AeroDataBoxSource(properties);
        };
    }

    @Bean
    public BatchValidator batchValidator(FlightBoardProperties properties, ObjectMapper mapper) {
        return new BatchValidator(properties.board(), mapper);
    }
}
