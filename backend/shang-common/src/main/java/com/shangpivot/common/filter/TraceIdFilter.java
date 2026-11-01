package com.shangpivot.common.filter;

import com.shangpivot.common.util.TraceIdUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 最早执行：生成/继承 X-Trace-Id，写入 MDC，并回写响应头。
 * 保证后续日志与统一响应体 Result.traceId 同源。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TraceIdUtil.HEADER);
        String traceId = TraceIdUtil.getOrCreate(incoming);
        TraceIdUtil.set(traceId);
        response.setHeader(TraceIdUtil.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceIdUtil.clear();
        }
    }
}
