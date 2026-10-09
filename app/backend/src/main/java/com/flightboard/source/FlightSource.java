package com.flightboard.source;

import java.time.Duration;

/** A single complete source response; validation happens before any publication. */
public interface FlightSource extends AutoCloseable {
    String fetch() throws SourceException;

    default String fetch(Duration remaining) throws SourceException {
        return fetch();
    }

    @Override
    default void close() {}
}
