package com.shangpivot.customerservice.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangpivot.customerservice.agent.trace.entity.PlatformOutbox;
import com.shangpivot.customerservice.agent.trace.mapper.PlatformOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 生产真实会话推送：outbox 模式，业务事务只写事件，定时异步投递，失败指数退避。
 * 测试流量（isTest=true）默认不推送，由测试平台自己记录，避免真实指标被污染。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformWebhookService {

    private final PlatformOutboxMapper mapper;
    private final ObjectMapper objectMapper;

    @Value("${platform.webhook.url:}")
    private String url;

    @Value("${platform.webhook.secret:}")
    private String secret;

    @Value("${platform.webhook.enabled:false}")
    private boolean enabled;

    @Value("${platform.webhook.include-test:false}")
    private boolean includeTest;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public void enqueue(String eventType, Map<String, Object> payload, boolean isTest) {
        if (!enabled || url == null || url.isBlank() || (!includeTest && isTest)) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>(payload);
            body.putIfAbsent("eventId", UUID.randomUUID().toString().replace("-", ""));
            body.putIfAbsent("eventType", eventType);
            body.putIfAbsent("occurredAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            PlatformOutbox row = new PlatformOutbox();
            row.setEventId(String.valueOf(body.get("eventId")));
            row.setEventType(eventType);
            row.setPayloadJson(body);
            row.setStatus("PENDING");
            row.setRetryCount(0);
            row.setNextRetryAt(OffsetDateTime.now());
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("[cs] webhook enqueue failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${platform.webhook.flush-ms:5000}")
    public void flush() {
        if (!enabled || url == null || url.isBlank()) {
            return;
        }
        List<PlatformOutbox> rows = mapper.selectList(new LambdaQueryWrapper<PlatformOutbox>()
                .eq(PlatformOutbox::getStatus, "PENDING")
                .le(PlatformOutbox::getNextRetryAt, OffsetDateTime.now())
                .orderByAsc(PlatformOutbox::getId)
                .last("LIMIT 20"));
        for (PlatformOutbox row : rows) {
            send(row);
        }
    }

    private void send(PlatformOutbox row) {
        try {
            String body = objectMapper.writeValueAsString(row.getPayloadJson());
            long timestamp = System.currentTimeMillis();
            String signature = hmac(body + "\n" + timestamp);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Shang-Signature", signature)
                    .header("X-Shang-Timestamp", String.valueOf(timestamp))
                    .header("X-Shang-Event-Id", row.getEventId())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                row.setStatus("SENT");
                row.setSentAt(OffsetDateTime.now());
                row.setLastError("");
                mapper.updateById(row);
                return;
            }
            fail(row, "HTTP " + response.statusCode() + ": " + preview(response.body()));
        } catch (Exception e) {
            fail(row, e.getClass().getSimpleName() + ": " + preview(e.getMessage()));
        }
    }

    private void fail(PlatformOutbox row, String error) {
        int retry = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
        row.setRetryCount(retry);
        row.setLastError(preview(error));
        row.setStatus(retry >= 8 ? "FAILED" : "PENDING");
        long delaySeconds = Math.min(300, (long) Math.pow(2, Math.min(retry, 8)));
        row.setNextRetryAt(OffsetDateTime.now().plusSeconds(delaySeconds));
        mapper.updateById(row);
        log.warn("[cs] webhook send failed event={} retry={} err={}", row.getEventId(), retry, error);
    }

    private String hmac(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
