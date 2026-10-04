package com.example.cacheaside.web;

import org.slf4j.MDC;

public record ApiError(String code, String message, String requestId, boolean retryable) {
    public static ApiError of(String code, String message, boolean retryable) {
        return new ApiError(code, message, MDC.get("requestId"), retryable);
    }
}
