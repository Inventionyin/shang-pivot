package com.shangpivot.common.filter;

import com.shangpivot.common.util.TraceIdUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * HTTP 访问日志：方法、路径、查询串、客户端、用户、状态、耗时、请求/响应体（截断+脱敏）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final int MAX_BODY = 2048;
    private static final Set<String> SKIP_PREFIX = Set.of(
            "/actuator", "/favicon", "/doc.html", "/webjars",
            "/v3/api-docs", "/swagger", "/knife4j"
    );
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|secret|accessToken|refreshToken|token|apiKey|authorization)\"\\s*:\\s*)\"[^\"]*\"");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String p : SKIP_PREFIX) {
            if (uri.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        HttpServletRequest reqToUse = request;
        ContentCachingRequestWrapper cachedReq = null;
        if (isReadableBody(request.getContentType()) && request.getContentLengthLong() != 0) {
            cachedReq = new ContentCachingRequestWrapper(request, MAX_BODY);
            reqToUse = cachedReq;
        }
        // SSE（V3.0 智能客服 /api/chat/**）等流式响应不能被 ContentCachingResponseWrapper 包裹：
        // 初始请求线程返回后异步线程才写响应体，wrapper 会吞掉全部字节导致客户端空流。
        boolean streaming = isStreamingEndpoint(request);
        HttpServletResponse respToUse = streaming ? response : new ContentCachingResponseWrapper(response);
        ContentCachingResponseWrapper cachedResp = streaming ? null : (ContentCachingResponseWrapper) respToUse;

        String query = request.getQueryString();
        String path = request.getRequestURI() + (StringUtils.hasText(query) ? "?" + query : "");
        String client = clientIp(request);
        log.info("→ {} {} | ip={} | ct={}",
                request.getMethod(), path, client,
                StringUtils.hasText(request.getContentType()) ? request.getContentType() : "-");

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(reqToUse, respToUse);
        } finally {
            long cost = System.currentTimeMillis() - start;
            String reqBody = cachedReq == null ? "" : truncate(mask(readBody(cachedReq.getContentAsByteArray(), request.getCharacterEncoding())));
            String respBody = "";
            if (cachedResp != null && isReadableBody(cachedResp.getContentType())) {
                respBody = truncate(mask(readBody(cachedResp.getContentAsByteArray(), cachedResp.getCharacterEncoding())));
            }
            if (respToUse.getStatus() >= 400) {
                log.warn("← {} {} | status={} | {}ms | ip={} | user={}/{} | req={} | resp={}",
                        request.getMethod(), path, respToUse.getStatus(), cost, client,
                        TraceIdUtil.userId(), TraceIdUtil.username(),
                        blankToDash(reqBody), blankToDash(respBody));
            } else {
                log.info("← {} {} | status={} | {}ms | ip={} | user={}/{} | req={} | resp={}",
                        request.getMethod(), path, respToUse.getStatus(), cost, client,
                        TraceIdUtil.userId(), TraceIdUtil.username(),
                        blankToDash(reqBody), blankToDash(respBody));
            }
            if (cachedResp != null) {
                cachedResp.copyBodyToResponse();
            }
        }
    }

    /** 流式端点：响应直传不包装（SSE 事件流）。 */
    private static boolean isStreamingEndpoint(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/chat/");
    }

    private static boolean isReadableBody(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return false;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.contains(MediaType.APPLICATION_JSON_VALUE)
                || ct.contains("application/x-www-form-urlencoded")
                || ct.contains("text/plain")
                || ct.contains("text/xml")
                || ct.contains("+json");
    }

    private static String readBody(byte[] bytes, String encoding) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        // JSON 一律按 UTF-8；Tomcat 未显式设 charset 时常落成 ISO-8859-1，日志会乱码
        Charset cs = StandardCharsets.UTF_8;
        if (StringUtils.hasText(encoding)
                && !"ISO-8859-1".equalsIgnoreCase(encoding)
                && !"ISO_8859_1".equalsIgnoreCase(encoding)) {
            try {
                cs = Charset.forName(encoding);
            } catch (Exception ignored) {
                // keep utf-8
            }
        }
        return new String(bytes, cs).replaceAll("\\s+", " ").trim();
    }

    private static String mask(String body) {
        if (!StringUtils.hasText(body)) {
            return body;
        }
        return SENSITIVE.matcher(body).replaceAll("$1\"***\"");
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= MAX_BODY) {
            return body;
        }
        return body.substring(0, MAX_BODY) + "...(truncated)";
    }

    private static String blankToDash(String s) {
        return StringUtils.hasText(s) ? s : "-";
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
