package com.example.whatsapp.exception;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        log.error("Unhandled exception caught", ex);
        return new ResponseEntity<>(new ErrorResponse("error", "An unexpected error occurred"), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ErrorResponse> handleRestClientException(RestClientException ex) {
        log.error("REST client error", ex);
        return new ResponseEntity<>(new ErrorResponse("error", "Failed to communicate with downstream service"), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Data
    public static class ErrorResponse {
        private final String status;
        private final String message;
        
        public ErrorResponse(String status, String message) {
            this.status = status;
            this.message = message;
        }
    }
}
