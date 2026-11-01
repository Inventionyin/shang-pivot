package com.shangpivot.identity.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.result.Result;
import com.shangpivot.identity.application.OperationLogService;
import com.shangpivot.identity.entity.OperationLog;
import com.shangpivot.identity.mapper.OperationLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 操作审计日志查询与 CSV 导出 API。
 * <p>路径前缀 /admin/operation-logs；导出最多 5000 条。
 */
@RestController
@RequestMapping("/admin/operation-logs")
@RequiredArgsConstructor
@Tag(name = "操作日志", description = "后台操作审计日志查询")
public class OperationLogController {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int EXPORT_LIMIT = 5000;

    private final OperationLogMapper operationLogMapper;
    private final OperationLogService operationLogService;

    @GetMapping
    @Operation(summary = "分页查询操作日志", description = "按用户名/操作内容、模块、用户ID、日期范围筛选")
    public Result<PageResult<OperationLog>> page(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "用户名或操作内容（模糊匹配）", example = "admin") @RequestParam(required = false) String username,
            @Parameter(description = "操作模块", example = "auth") @RequestParam(required = false) String module,
            @Parameter(description = "操作人用户ID", example = "1") @RequestParam(required = false) Long userId,
            @Parameter(description = "开始日期 yyyy-MM-dd（含当日）", example = "2026-09-01") @RequestParam(required = false) String from,
            @Parameter(description = "结束日期 yyyy-MM-dd（含当日）", example = "2026-09-19") @RequestParam(required = false) String to) {
        Page<OperationLog> p = operationLogMapper.selectPage(new Page<>(page, size),
                buildQuery(username, module, userId, from, to));
        return Result.ok(PageResult.of(p.getTotal(), p.getRecords()));
    }

    @GetMapping("/export")
    @Operation(summary = "导出操作日志", description = "按当前筛选条件导出 CSV（最多 5000 条）")
    public void export(
            @Parameter(description = "用户名或操作内容（模糊匹配）") @RequestParam(required = false) String username,
            @Parameter(description = "操作模块") @RequestParam(required = false) String module,
            @Parameter(description = "操作人用户ID") @RequestParam(required = false) Long userId,
            @Parameter(description = "开始日期 yyyy-MM-dd") @RequestParam(required = false) String from,
            @Parameter(description = "结束日期 yyyy-MM-dd") @RequestParam(required = false) String to,
            HttpServletResponse response) throws Exception {
        List<OperationLog> rows = operationLogMapper.selectList(
                buildQuery(username, module, userId, from, to).last("LIMIT " + EXPORT_LIMIT));

        String filename = "operation-logs-" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv";
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8));

        try (Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write("用户,模块,操作,参数,IP,结果,时间,TraceId,User-Agent\n");
            for (OperationLog row : rows) {
                writer.write(csv(row.getUsername()));
                writer.write(',');
                writer.write(csv(row.getModule()));
                writer.write(',');
                writer.write(csv(row.getAction()));
                writer.write(',');
                writer.write(csv(row.getParams()));
                writer.write(',');
                writer.write(csv(row.getIp()));
                writer.write(',');
                writer.write(csv(row.getResult()));
                writer.write(',');
                writer.write(csv(formatTime(row.getCreatedAt())));
                writer.write(',');
                writer.write(csv(row.getTraceId()));
                writer.write(',');
                writer.write(csv(row.getUa()));
                writer.write('\n');
            }
            writer.flush();
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "操作日志详情", description = "根据日志 ID 查看完整审计信息（含参数、UA、TraceId）")
    public Result<OperationLog> detail(
            @Parameter(description = "日志ID", example = "1") @PathVariable Long id) {
        return Result.ok(operationLogService.getById(id));
    }

    private LambdaQueryWrapper<OperationLog> buildQuery(
            String username, String module, Long userId, String from, String to) {
        LambdaQueryWrapper<OperationLog> q = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(username)) {
            q.and(w -> w.like(OperationLog::getUsername, username)
                    .or().like(OperationLog::getAction, username)
                    .or().like(OperationLog::getParams, username));
        }
        q.eq(StringUtils.hasText(module), OperationLog::getModule, module)
                .eq(userId != null, OperationLog::getUserId, userId);
        OffsetDateTime fromAt = parseStartOfDay(from);
        OffsetDateTime toAt = parseEndOfDay(to);
        if (fromAt != null) {
            q.ge(OperationLog::getCreatedAt, fromAt);
        }
        if (toAt != null) {
            q.le(OperationLog::getCreatedAt, toAt);
        }
        q.orderByDesc(OperationLog::getId);
        return q;
    }

    private static OffsetDateTime parseStartOfDay(String date) {
        LocalDate d = parseDate(date);
        if (d == null) {
            return null;
        }
        return d.atStartOfDay(ZONE).toOffsetDateTime();
    }

    private static OffsetDateTime parseEndOfDay(String date) {
        LocalDate d = parseDate(date);
        if (d == null) {
            return null;
        }
        return d.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime().minusNanos(1);
    }

    private static LocalDate parseDate(String date) {
        if (!StringUtils.hasText(date)) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String formatTime(OffsetDateTime time) {
        if (time == null) {
            return "";
        }
        return time.atZoneSameInstant(ZONE).format(TIME_FMT);
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
