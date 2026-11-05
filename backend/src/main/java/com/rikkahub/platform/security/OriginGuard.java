package com.rikkahub.platform.security;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Validates the actual local authority before accepting any ambient browser credential. */
public final class OriginGuard extends OncePerRequestFilter {
    private static final Pattern AUTHORITY = Pattern.compile("(?i)(localhost|127\\.0\\.0\\.1|\\[::1\\])(?::([0-9]{1,5}))?");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> CORS_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Set<String> CORS_HEADERS = Set.of("content-type", "x-rikkahub-token", "last-event-id", "idempotency-key");
    private final LocalTokenService tokens;
    private final int port;
    private final boolean developmentCors;

    public OriginGuard(LocalTokenService tokens, int port, boolean developmentCors) {
        if (port < 0 || port > 65535) { throw new IllegalArgumentException("Invalid local server port"); }
        this.tokens = Objects.requireNonNull(tokens); this.port = port; this.developmentCors = developmentCors;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");

        boolean externalWebhook = request.getRequestURI().startsWith("/api/v1/webhooks/in/");
        if (externalWebhook) {
            chain.doFilter(request, response);
            return;
        }

        String host = singleHeader(request, "Host");
        var authority = host == null ? null : AUTHORITY.matcher(host);
        int expectedPort = port == 0 ? request.getLocalPort() : port;
        if (authority == null || !authority.matches()) { reject(request, response, "HOST_NOT_ALLOWED"); return; }
        int actualPort = authority.group(2) == null ? defaultPort(request.getScheme()) : Integer.parseInt(authority.group(2));
        if (actualPort != expectedPort) { reject(request, response, "HOST_NOT_ALLOWED"); return; }
        String origin = singleHeader(request, "Origin");
        boolean hasOrigin = request.getHeader("Origin") != null;
        boolean allowedOrigin = origin != null && allowedOrigin(origin, request.getScheme(), host);
        if (hasOrigin && !allowedOrigin) { reject(request, response, "ORIGIN_NOT_ALLOWED"); return; }
        boolean devOrigin = developmentCors && "http://localhost:5173".equals(origin);
        if (devOrigin) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.addHeader("Vary", "Origin");
        }
        if ("OPTIONS".equals(request.getMethod()) && request.getHeader("Access-Control-Request-Method") != null) {
            String method = request.getHeader("Access-Control-Request-Method");
            String requested = request.getHeader("Access-Control-Request-Headers");
            if (!allowedOrigin || !CORS_METHODS.contains(method) || (requested != null && Arrays.stream(requested.split(","))
                    .map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(s -> !CORS_HEADERS.contains(s)))) {
                reject(request, response, "ORIGIN_NOT_ALLOWED"); return;
            }
            response.setHeader("Access-Control-Allow-Methods", "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, X-RikkaHub-Token, Last-Event-ID, Idempotency-Key");
            response.setStatus(HttpServletResponse.SC_NO_CONTENT); return;
        }
        String header = singleHeader(request, LocalTokenService.HEADER_NAME);
        boolean headerAuthenticated = tokens.matches(header);
        boolean browserSameOrigin = !hasOrigin && "same-origin".equals(request.getHeader("Sec-Fetch-Site"));
        boolean bootstrap = "GET".equals(request.getMethod()) && "/api/v1/setup/session".equals(request.getRequestURI());
        if (bootstrap) {
            if (!headerAuthenticated && !allowedOrigin && !browserSameOrigin) { reject(request, response, "LOCAL_AUTH_REQUIRED"); return; }
        } else if (request.getRequestURI().startsWith("/api/") || !SAFE_METHODS.contains(request.getMethod())) {
            boolean cookieAuthenticated = (allowedOrigin || browserSameOrigin) && validCookie(request);
            if (!headerAuthenticated && !cookieAuthenticated) { reject(request, response, "LOCAL_AUTH_REQUIRED"); return; }
        }
        chain.doFilter(request, response);
    }

    private boolean validCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) { return false; }
        return Arrays.stream(cookies).anyMatch(cookie -> LocalTokenService.COOKIE_NAME.equals(cookie.getName()) && tokens.matches(cookie.getValue()));
    }

    private boolean allowedOrigin(String value, String scheme, String host) {
        if (developmentCors && "http://localhost:5173".equals(value)) { return true; }
        try {
            URI uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getScheme().equals(scheme)
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawAuthority() != null && uri.getRawAuthority().equalsIgnoreCase(host);
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.get(0) : null;
    }
    private static int defaultPort(String scheme) { return "https".equals(scheme) ? 443 : 80; }

    private static void reject(HttpServletRequest request, HttpServletResponse response, String code) throws IOException {
        Object supplied = request.getAttribute("traceId");
        String trace = supplied instanceof String value && value.matches("[A-Za-z0-9._:-]{1,128}") ? value : UUID.randomUUID().toString();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Trace-Id", trace);
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Local request denied\",\"status\":403,\"code\":\""
                + code + "\",\"traceId\":\"" + trace + "\",\"details\":{}}");
    }
}
