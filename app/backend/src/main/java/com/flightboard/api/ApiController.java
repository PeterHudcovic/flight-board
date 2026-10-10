package com.flightboard.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final ApiService service;
    public ApiController(ApiService service) { this.service = service; }
    @GetMapping("/departures") public ApiService.Departures departures() { return service.departures(); }
    @GetMapping("/status") public ApiService.Status status() { return service.status(); }
}
