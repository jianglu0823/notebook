package io.llmnote.schedule;

import io.llmnote.auth.Principal;
import io.llmnote.config.NotebookLmProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Set;

/**
 * schedule-agent 反代 (前置 UI 集成):
 *  - /api/schedule/v1/**  →  http://127.0.0.1:8081/api/schedule/v1/**
 *  - 透传 method / query / body
 *  - 注入 X-User-Id(从 Principal;游客 -1)
 *  - /api/schedule/v1/internal/** 再加 X-Schedule-Agent-Token
 *  - 透传响应头(Content-Type、status)与 body 字节
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleProxyController {

    private static final Logger log = LoggerFactory.getLogger(ScheduleProxyController.class);

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String INTERNAL_TOKEN_HEADER = "X-Schedule-Agent-Token";
    private static final String INTERNAL_PATH_PREFIX = "/api/schedule/v1/internal/";
    private static final Set<String> HOP_BY_HOP = Set.of(
            "host", "connection", "content-length", "transfer-encoding",
            USER_ID_HEADER.toLowerCase(), INTERNAL_TOKEN_HEADER.toLowerCase()
    );
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final NotebookLmProperties props;

    public ScheduleProxyController(NotebookLmProperties props) {
        this.props = props;
    }

    @RequestMapping("/v1/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response, Principal principal) throws IOException {
        String suffix = extractSuffix(request);
        String target = props.getScheduleAgent().getBaseUrl() + "/api/schedule/v1" + suffix;
        if (log.isDebugEnabled()) {
            log.debug("schedule proxy {} {} -> {}", request.getMethod(), request.getRequestURI(), target);
        }

        HttpURLConnection conn = openConn(target, request);
        try {
            copyRequestHeaders(request, conn);
            conn.setRequestProperty(USER_ID_HEADER, principal != null && principal.userId() != null
                    ? String.valueOf(principal.userId()) : "-1");
            if (target.contains("/api/schedule/v1/internal/")) {
                String token = props.getScheduleAgent().getInternalToken();
                if (token == null || token.isBlank()) {
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"error\":\"schedule-agent internal token not configured\"}");
                    return;
                }
                conn.setRequestProperty(INTERNAL_TOKEN_HEADER, token);
            }

            if (hasRequestBody(request)) {
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream();
                     InputStream in = request.getInputStream()) {
                    in.transferTo(out);
                }
            }

            int upstreamStatus = conn.getResponseCode();
            response.setStatus(upstreamStatus);
            copyResponseHeaders(conn, response);
            InputStream bodyStream = upstreamStatus >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (bodyStream != null) {
                try (OutputStream out = response.getOutputStream()) {
                    bodyStream.transferTo(out);
                }
            }
        } catch (IOException e) {
            log.warn("schedule proxy failed: target={} err={}", target, e.toString());
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"schedule-agent upstream unreachable: " +
                        e.getClass().getSimpleName() + "\"}");
            }
        } finally {
            conn.disconnect();
        }
    }

    private String extractSuffix(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int idx = uri.indexOf("/api/schedule/v1");
        if (idx < 0) return "";
        String suffix = uri.substring(idx + "/api/schedule/v1".length());
        String query = request.getQueryString();
        return query == null || query.isEmpty() ? suffix : suffix + "?" + query;
    }

    private HttpURLConnection openConn(String target, HttpServletRequest request) throws IOException {
        URL url;
        try {
            url = new URI(target).toURL();
        } catch (URISyntaxException e) {
            throw new IOException("invalid target url: " + target, e);
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestMethod(request.getMethod());
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private boolean hasRequestBody(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpURLConnection conn) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase())) continue;
            String value = request.getHeader(name);
            if (value != null) conn.setRequestProperty(name, value);
        }
    }

    private void copyResponseHeaders(HttpURLConnection conn, HttpServletResponse response) {
        for (int i = 0; ; i++) {
            String name = conn.getHeaderFieldKey(i);
            String value = conn.getHeaderField(i);
            if (name == null && value == null) break;
            if (name != null && HOP_BY_HOP.contains(name.toLowerCase())) continue;
            if (name == null) continue;
            response.addHeader(name, value);
        }
    }
}
