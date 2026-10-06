package com.example.cacheaside.demo;

import com.example.cacheaside.web.ApiError;
import com.example.cacheaside.web.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RunRequestFilter extends OncePerRequestFilter {
    private final RunGuard guard;
    private final JsonMapper json;
    public RunRequestFilter(RunGuard guard, JsonMapper json) { this.guard = guard; this.json = json; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader("X-Lab-Dispatch");
        if (token == null) { chain.doFilter(request, response); return; }
        try (var scope = guard.enter(token)) {
            response.setHeader("X-Run-Id", MDC.get("runId"));
            chain.doFilter(request, response);
        } catch (ApiException closed) {
            response.setStatus(closed.status().value());
            response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), ApiError.of(closed.code(), closed.getMessage(), closed.retryable()));
        }
    }
}
