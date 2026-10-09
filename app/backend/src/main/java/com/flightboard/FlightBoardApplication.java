package com.flightboard;

import com.flightboard.config.FlightBoardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FlightBoardProperties.class)
public class FlightBoardApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlightBoardApplication.class, args);
    }
}
