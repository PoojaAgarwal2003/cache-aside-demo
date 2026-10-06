package com.example.cacheaside.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBoundaryFilter extends OncePerRequestFilter {
    private static final int MAX_BODY = 8192;
    private static final Set<String> MUTATIONS = Set.of("POST", "PATCH", "PUT", "DELETE");
    private final LabProperties properties;
    private final JsonMapper mapper;

    public RequestBoundaryFilter(LabProperties properties, JsonMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; "
                + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        response.setHeader("Referrer-Policy", "no-referrer");
        try {
            String host = request.getHeader("Host");
            int port = request.getLocalPort();
            if (!("127.0.0.1:" + port).equals(host) && !("localhost:" + port).equals(host)
                    && !(port == 80 && ("127.0.0.1".equals(host) || "localhost".equals(host)))) {
                reject(response, 403, "LOCAL_ONLY", "Use this application's loopback address.");
                return;
            }
            String client = request.getHeader("X-Client-Id");
            if (client != null && !client.matches("[A-Za-z0-9._-]{1,64}")) {
                reject(response, 400, "INVALID_REQUEST", "X-Client-Id must be 1-64 safe ASCII characters.");
                return;
            }
            if (MUTATIONS.contains(request.getMethod())) {
                if (!properties.demoEnabled()) {
                    reject(response, 403, "DEMO_DISABLED", "Mutations require the explicit demo/benchmark profile.");
                    return;
                }
                String origin = request.getHeader("Origin");
                if (origin != null && !origin.equals("http://" + host)
                        || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
                    reject(response, 403, "ORIGIN_REJECTED", "State-changing requests must be same-origin.");
                    return;
                }
            }
            if (request.getContentLengthLong() > MAX_BODY) {
                reject(response, 413, "BODY_TOO_LARGE", "JSON bodies are limited to 8192 bytes.");
                return;
            }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                reject(response, 413, "BODY_TOO_LARGE", "JSON bodies are limited to 8192 bytes.");
                return;
            }
            if (body.length > 0 && (request.getContentType() == null
                    || !isJson(request.getContentType()))) {
                reject(response, 415, "UNSUPPORTED_MEDIA_TYPE", "Use Content-Type: application/json.");
                return;
            }
            chain.doFilter(new BufferedRequest(request, body), response);
        } finally {
            MDC.remove("requestId");
        }
    }

    private boolean isJson(String contentType) {
        try {
            return MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), ApiError.of(code, message, false));
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous JSON endpoints only.");
                }
            };
        }
    }
}
