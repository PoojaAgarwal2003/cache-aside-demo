package com.example.cacheaside.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException error) {
        return ResponseEntity.status(error.status())
                .body(ApiError.of(error.code(), error.getMessage(), error.retryable()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> invalid(Exception error) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("INVALID_REQUEST", "Invalid JSON, parameters or field values.", false));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> conflict(ObjectOptimisticLockingFailureException error) {
        return ResponseEntity.status(409).body(ApiError.of("VERSION_CONFLICT",
                "The product changed concurrently; read it again before retrying.", true));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ApiError> database(Exception error) {
        LOG.warn("Database operation failed (type={})", error.getClass().getSimpleName());
        return ResponseEntity.status(503).body(ApiError.of("DATABASE_UNAVAILABLE",
                "Database operation unavailable or timed out. Retry purchases with the same key.", true));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(NoResourceFoundException error) {
        return ResponseEntity.status(404).body(ApiError.of("NOT_FOUND", "Route not found.", false));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception error) {
        LOG.error("Unexpected request failure", error);
        return ResponseEntity.internalServerError()
                .body(ApiError.of("INTERNAL_ERROR", "Unexpected request failure.", false));
    }
}
