package com.flightboard.persistence;

import com.flightboard.validation.BoardFlight;
import java.time.Instant;
import java.util.List;

public record BoardSnapshot(String runId, long fetchSeq, Instant publishedAt, List<BoardFlight> flights) {
    public BoardSnapshot { flights = List.copyOf(flights); }
}
