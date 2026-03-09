package com.slmplatform.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ApiSecurityFilter extends OncePerRequestFilter {

    @Value("${platform.api.master-key}")
    private String masterApiKey;

    private final Map<String, Bucket> cache = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Disable security for local dev OPTIONS requests (CORS) or logs for the demo
        // UI
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || request.getRequestURI().contains("/logs")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Rate Limiting (20 requests / min)
        Bucket bucket = cache.computeIfAbsent("demo-user", this::createNewBucket);
        if (!bucket.tryConsume(1)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Bucket createNewBucket(String key) {
        Refill refill = Refill.greedy(20, Duration.ofMinutes(1));
        Bandwidth limit = Bandwidth.classic(20, refill);
        return Bucket.builder().addLimit(limit).build();
    }
}