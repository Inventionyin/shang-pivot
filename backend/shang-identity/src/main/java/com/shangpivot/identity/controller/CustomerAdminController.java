package com.shangpivot.identity.controller;

import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.result.PageResult;
import com.shangpivot.common.result.Result;
import com.shangpivot.identity.application.CustomerAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/customers")
@RequiredArgsConstructor
@Tag(name = "会员管理", description = "C 端会员查询与冻结")
public class CustomerAdminController {

    private final CustomerAdminService customerAdminService;

    @GetMapping
    @Operation(summary = "分页查询会员")
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String nickname,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String clientType) {
        return Result.ok(customerAdminService.page(page, size, phone, nickname, status, clientType));
    }

    @GetMapping("/{id}")
    @Operation(summary = "会员详情")
    public Result<Map<String, Object>> detail(
            @Parameter(description = "会员ID") @PathVariable Long id) {
        return Result.ok(customerAdminService.detail(id));
    }

    @PutMapping("/{id}/status")
    @OpLog(module = "customer", action = "status")
    @Operation(summary = "冻结/解冻会员")
    public Result<Void> updateStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        customerAdminService.updateStatus(id, body.get("status"));
        return Result.ok();
    }
}
