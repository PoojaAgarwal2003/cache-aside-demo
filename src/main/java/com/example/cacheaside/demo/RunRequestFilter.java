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
    private final DatabaseWork database;
    public RunRequestFilter(RunGuard guard, JsonMapper json, DatabaseWork database) {
        this.guard = guard; this.json = json; this.database = database;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader("X-Lab-Dispatch");
        if (token == null) { chain.doFilter(request, response); return; }
        var purpose = request.getServletPath().endsWith("/purchase") ? DatabaseWork.Purpose.PURCHASE
                : request.getMethod().equals("GET") && request.getServletPath().startsWith("/products/")
                ? DatabaseWork.Purpose.USER_READ : DatabaseWork.Purpose.LISTENER_ADMIN;
        String caseIndex = request.getHeader("X-Lab-Case");
        if (caseIndex == null || !caseIndex.matches("[0-4]")) {
            response.setStatus(400);
            response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), ApiError.of("INVALID_RUN_CASE", "Run case must be 0-4.", false));
            return;
        }
        try (var scope = guard.enter(token); var sql = database.purpose(purpose);
             var perCase = database.caseScope(Integer.parseInt(caseIndex))) {
            response.setHeader("X-Run-Id", MDC.get("runId"));
            chain.doFilter(request, response);
        } catch (ApiException closed) {
            response.setStatus(closed.status().value());
            response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), ApiError.of(closed.code(), closed.getMessage(), closed.retryable()));
        }
    }
}
