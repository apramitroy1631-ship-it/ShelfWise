package com.shelfwise.core.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;

import tools.jackson.core.JacksonException;

/**
 * A request body with a value of the wrong kind, e.g. a quantity of 1.5 (review issue 4: it used to be saved as 1).
 * Instead of a generic "Failed to read HTTP message", say which field is wrong.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)   // before Spring Boot's generic problem-details handler
public class BadInputAdvice {

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ProblemDetail> badInput(ServerWebInputException e) {
        String detail = e.getReason() == null ? "The request is not valid" : e.getReason();
        if (e instanceof WebExchangeBindException bind && !bind.getFieldErrors().isEmpty()) {
            detail = bind.getFieldErrors().getFirst().getDefaultMessage();          // e.g. "quantity must be at least 1"
        }
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException mismatch) {
                String field = field(mismatch);
                String text = String.valueOf(mismatch.getOriginalMessage());
                detail = text.contains("Floating-point") ? field + " must be a whole number" : field + " has the wrong type of value";
                break;
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail));
    }

    /** The innermost named field, e.g. "qty" for lines[0].qty. */
    private static String field(JacksonException e) {
        String name = "a value";
        for (JacksonException.Reference ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                name = ref.getPropertyName();
            }
        }
        return name;
    }
}
