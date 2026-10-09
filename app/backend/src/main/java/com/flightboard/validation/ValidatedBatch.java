package com.flightboard.validation;

import java.util.List;

public record ValidatedBatch(List<BoardFlight> flights, int sourceFlightCount) {
    public ValidatedBatch {
        flights = List.copyOf(flights);
    }
}
