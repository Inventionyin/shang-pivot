package com.shangpivot.identity.config;

import tools.jackson.databind.ObjectMapper;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.Result;
import com.shangpivot.common.util.TraceIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class RestAuthEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        log.warn("Unauthorized {} {} : {}", request.getMethod(), request.getRequestURI(), authException.getMessage());
        write(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       org.springframework.security.access.AccessDeniedException accessDeniedException) throws IOException {
        log.warn("Forbidden {} {} : {}", request.getMethod(), request.getRequestURI(), accessDeniedException.getMessage());
        write(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, int status, ErrorCode code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(TraceIdUtil.HEADER, TraceIdUtil.get());
        objectMapper.writeValue(response.getOutputStream(), Result.fail(code));
    }
}
