package com.example.cacheaside.ratelimit;

import com.example.cacheaside.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter limiter;
    private final JsonMapper json;

    public RateLimitFilter(RateLimiter limiter, JsonMapper json) {
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.equals("/products") && !path.startsWith("/products/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String identity = request.getHeader("X-Client-Id");
        var decision = limiter.decide(identity == null ? "local" : identity);
        response.setHeader("X-RateLimit-Status", decision.outcome().name());
        if (decision.limit() != null) {
            response.setHeader("X-RateLimit-Limit", decision.limit().toString());
            response.setHeader("X-RateLimit-Remaining", decision.remaining().toString());
        }
        if (decision.outcome() == RateLimiter.Outcome.REJECTED) {
            response.setStatus(429);
            response.setHeader("Retry-After", Integer.toString(Math.max(1, decision.retryAfterSeconds())));
            response.setContentType("application/json");
            json.writeValue(response.getOutputStream(), ApiError.of("RATE_LIMITED",
                    "Controlled client exceeded the sliding request window; retry after the indicated delay.", true));
            return;
        }
        chain.doFilter(request, response);
    }
}
