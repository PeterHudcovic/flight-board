package com.flightboard.validation;

import java.time.Instant;

/** Validated board data; scheduledAt retains the full date and instant for ordering. */
public record BoardFlight(
        String number, Instant scheduledAt, String scheduled, String expected, String destination,
        String checkIn, String bagDrop, String remark, RemarkColor remarkColor, String terminal) {
    public enum RemarkColor { WHITE, YELLOW, RED }
}
