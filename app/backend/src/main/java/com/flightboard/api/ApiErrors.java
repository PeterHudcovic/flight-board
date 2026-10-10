package com.flightboard.api;

import com.flightboard.fetch.RunDeadline;
import com.flightboard.persistence.StoreException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ApiController.class)
public class ApiErrors {
    @ExceptionHandler(ApiService.NoDataException.class)
    public ResponseEntity<ErrorBody> noData() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorBody("NO_DATA", "Flight information is temporarily unavailable"));
    }

    @ExceptionHandler({StoreException.class, RunDeadline.RunTimeoutException.class})
    public ResponseEntity<ErrorBody> database() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorBody("DATABASE_UNAVAILABLE", "Database is temporarily unavailable"));
    }

    public record ErrorBody(String code, String message) {}
}
