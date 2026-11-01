package com.shangpivot.identity.application;

import cn.hutool.json.JSONUtil;
import com.shangpivot.common.audit.OperationLogRecorder;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.security.LoginUser;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.common.util.TraceIdUtil;
import com.shangpivot.identity.entity.OperationLog;
import com.shangpivot.identity.mapper.OperationLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * 操作审计落库实现（{@link OperationLogRecorder}）：异步写入、参数脱敏截断。
 * <p>关联表：operation_log；敏感字段 password/token 等掩码为 ***。
 */
@Slf4j
@Service
public class OperationLogService implements OperationLogRecorder {

    private static final int MAX_PARAMS = 2000;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|secret|accessToken|refreshToken|token|apiKey|authorization|configValue)\"\\s*:\\s*)\"[^\"]*\"");

    private final OperationLogMapper operationLogMapper;
    private final Executor operationLogExecutor;

    public OperationLogService(
            OperationLogMapper operationLogMapper,
            @Qualifier("operationLogExecutor") Executor operationLogExecutor) {
        this.operationLogMapper = operationLogMapper;
        this.operationLogExecutor = operationLogExecutor;
    }

    public OperationLog getById(Long id) {
        OperationLog logEntity = operationLogMapper.selectById(id);
        if (logEntity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "操作日志不存在");
        }
        return logEntity;
    }

    /** 从当前请求上下文采集操作人 / IP / UA，异步落库。 */
    @Override
    public void record(String module, String action, Object params, String result) {
        Snapshot snap = Snapshot.capture(params);
        persistAsync(module, action, snap, result);
    }

    /**
     * 显式指定操作人/IP/UA 时落库（如登录未建上下文）；缺省字段回落请求快照。
     */
    @Override
    public void record(String module, String action, Object params, String result,
                       Long userId, String username, String ip, String ua) {
        Snapshot fromReq = Snapshot.capture(null);
        Snapshot snap = Snapshot.of(
                userId != null ? userId : fromReq.userId(),
                StringUtils.hasText(username) ? username : fromReq.username(),
                StringUtils.hasText(ip) ? ip : fromReq.ip(),
                StringUtils.hasText(ua) ? ua : fromReq.ua(),
                TraceIdUtil.get(),
                params);
        persistAsync(module, action, snap, result);
    }

    private void persistAsync(String module, String action, Snapshot snap, String result) {
        operationLogExecutor.execute(() -> {
            try {
                OperationLog opLog = new OperationLog();
                opLog.setUserId(snap.userId());
                opLog.setUsername(snap.username());
                opLog.setModule(module);
                opLog.setAction(action);
                opLog.setParams(snap.params());
                opLog.setIp(snap.ip());
                opLog.setUa(snap.ua());
                opLog.setResult(result);
                if (StringUtils.hasText(snap.traceId())) {
                    opLog.setTraceId(snap.traceId());
                }
                operationLogMapper.insert(opLog);
            } catch (Exception e) {
                log.warn("Failed to persist operation log module={} action={}: {}",
                        module, action, e.getMessage());
            }
        });
    }

    /** 将入参序列化为 JSON，脱敏并截断至 2000 字符。 */
    public static String toParamsJson(Object params) {
        if (params == null) {
            return null;
        }
        try {
            String json = params instanceof String s ? s : JSONUtil.toJsonStr(params);
            return truncate(mask(json));
        } catch (Exception e) {
            return truncate(String.valueOf(params));
        }
    }

    private static String mask(String body) {
        if (!StringUtils.hasText(body)) {
            return body;
        }
        return SENSITIVE.matcher(body).replaceAll("$1\"***\"");
    }

    private static String truncate(String body) {
        if (body == null) {
            return null;
        }
        if (body.length() <= MAX_PARAMS) {
            return body;
        }
        return body.substring(0, MAX_PARAMS) + "...(truncated)";
    }

    public record Snapshot(Long userId, String username, String ip, String ua, String traceId, String params) {
        static Snapshot capture(Object params) {
            Long userId = null;
            String username = null;
            LoginUser user = SecurityContexts.get();
            if (user != null) {
                userId = user.getUserId();
                username = user.getUsername();
            }
            String ip = null;
            String ua = null;
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                ip = clientIp(request);
                ua = truncateUa(request.getHeader("User-Agent"));
            }
            return of(userId, username, ip, ua, TraceIdUtil.get(), params);
        }

        static Snapshot of(Long userId, String username, String ip, String ua, String traceId, Object params) {
            return new Snapshot(userId, username, ip, ua, traceId, toParamsJson(params));
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

        private static String truncateUa(String ua) {
            if (!StringUtils.hasText(ua)) {
                return null;
            }
            return ua.length() <= 512 ? ua : ua.substring(0, 512);
        }
    }
}
