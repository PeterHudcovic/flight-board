package com.flightboard.source;

/** A single complete source response; validation happens before any publication. */
public interface FlightSource extends AutoCloseable {
    String fetch() throws SourceException;

    @Override
    default void close() {}
}
