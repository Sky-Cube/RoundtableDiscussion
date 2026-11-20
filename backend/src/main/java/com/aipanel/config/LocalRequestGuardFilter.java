package com.aipanel.config;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.aipanel.exception.ApiErrorCode;
import com.aipanel.web.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 回环访问守卫：Host 必须属于本机，写请求携带的 Origin 必须是允许的本地前端来源。
 * 两者不满足时在进入控制器前返回 403/B0006。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LocalRequestGuardFilter extends OncePerRequestFilter {

    private final PanelProperties properties;
    private final ObjectMapper objectMapper;

    public LocalRequestGuardFilter(PanelProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        List<String> hosts = properties.request().allowedHosts();
        String hostName = hostName(request.getHeader("Host"));
        if (hostName != null && !hosts.contains(hostName)) {
            reject(response);
            return;
        }
        if (!isSafe(request.getMethod())) {
            String origin = request.getHeader("Origin");
            if (origin != null && !originAllowed(origin, hosts)) {
                reject(response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isSafe(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private boolean originAllowed(String origin, List<String> hosts) {
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || !hosts.contains(host)) {
                return false;
            }
            int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(scheme) ? 443 : 80) : uri.getPort();
            return properties.request().allowedOriginPorts().contains(port);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private String hostName(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String value = hostHeader.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        int first = value.indexOf(':');
        int last = value.lastIndexOf(':');
        return first == last && last > 0 ? value.substring(0, last) : value;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(ApiErrorCode.B0006.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(ApiErrorCode.B0006));
    }
}
