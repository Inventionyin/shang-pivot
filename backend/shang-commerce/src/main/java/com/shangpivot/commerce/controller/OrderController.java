package com.shangpivot.commerce.controller;

import com.shangpivot.commerce.application.OrderService;
import com.shangpivot.commerce.entity.Order;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 订单查询与发货 API。
 * <p>路径前缀 /admin/orders。
 */
@RestController
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
@Tag(name = "订单管理", description = "订单的查询、详情与发货")
public class OrderController {
    private final OrderService orderService;

    @GetMapping
    @Operation(summary = "分页查询订单", description = "按订单号、状态、客户名称、手机尾号、来源端筛选")
    public Result<PageResult<Order>> page(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "订单号（模糊匹配）") @RequestParam(required = false) String orderNo,
            @Parameter(description = "订单状态") @RequestParam(required = false) String status,
            @Parameter(description = "客户名称（模糊匹配）") @RequestParam(required = false) String customerName,
            @Parameter(description = "手机尾号") @RequestParam(required = false) String phoneTail,
            @Parameter(description = "来源端 web/h5") @RequestParam(required = false) String clientType,
            @Parameter(description = "下单起始时间") @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.OffsetDateTime createdFrom,
            @Parameter(description = "下单截止时间") @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.OffsetDateTime createdTo) {
        return Result.ok(orderService.page(page, size, orderNo, status, customerName, phoneTail, clientType, createdFrom, createdTo));
    }

    @GetMapping("/export")
    @Operation(summary = "导出订单 CSV", description = "按当前筛选导出，最多 5000 条")
    public void export(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String phoneTail,
            @RequestParam(required = false) String clientType,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.OffsetDateTime createdFrom,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.OffsetDateTime createdTo,
            HttpServletResponse response) throws Exception {
        List<Order> rows = orderService.listForExport(orderNo, status, customerName, phoneTail, clientType, createdFrom, createdTo);
        String filename = "orders-" + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv";
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8));
        try (Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            writer.write("订单号,顾客,手机脱敏,来源端,状态,商品金额,运费,实付,下单时间,支付时间\n");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            for (Order o : rows) {
                writer.write(csv(o.getOrderNo()));
                writer.write(',');
                writer.write(csv(o.getCustomerName()));
                writer.write(',');
                writer.write(csv(o.getCustomerPhoneMask()));
                writer.write(',');
                writer.write(csv(o.getClientType()));
                writer.write(',');
                writer.write(csv(o.getStatus()));
                writer.write(',');
                writer.write(csv(o.getTotalAmount() == null ? null : o.getTotalAmount().toPlainString()));
                writer.write(',');
                writer.write(csv(o.getFreight() == null ? null : o.getFreight().toPlainString()));
                writer.write(',');
                writer.write(csv(o.getPayAmount() == null ? null : o.getPayAmount().toPlainString()));
                writer.write(',');
                writer.write(csv(o.getCreatedAt() == null ? null : o.getCreatedAt().format(fmt)));
                writer.write(',');
                writer.write(csv(o.getPaidAt() == null ? null : o.getPaidAt().format(fmt)));
                writer.write('\n');
            }
            writer.flush();
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取订单详情", description = "返回订单信息、明细行及物流记录")
    public Result<Map<String, Object>> detail(
            @Parameter(description = "订单ID", example = "1") @PathVariable Long id) {
        return Result.ok(orderService.detail(id));
    }

    @PostMapping("/{id}/ship")
    @OpLog(module = "order", action = "ship")
    @Operation(summary = "订单发货", description = "对待发货订单录入物流信息并扣减库存")
    public Result<Void> ship(
            @Parameter(description = "订单ID", example = "1") @PathVariable Long id,
            @RequestBody OrderService.ShipReq req) {
        orderService.ship(id, req);
        return Result.ok();
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        String s = v.replace("\"", "\"\"");
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s + "\"";
        }
        return s;
    }
}
