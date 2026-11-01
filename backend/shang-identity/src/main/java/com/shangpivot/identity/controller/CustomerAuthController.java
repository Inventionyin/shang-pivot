package com.shangpivot.identity.controller;

import com.shangpivot.common.result.Result;
import com.shangpivot.identity.application.CustomerAuthService;
import com.shangpivot.identity.application.CustomerEmailService;
import com.shangpivot.identity.dto.CustomerLoginRequest;
import com.shangpivot.identity.dto.EmailLoginRequest;
import com.shangpivot.identity.dto.EmailSendRequest;
import com.shangpivot.identity.dto.RefreshRequest;
import com.shangpivot.identity.dto.SmsSendRequest;
import com.shangpivot.identity.vo.CustomerLoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * C 端认证 API。路径前缀 /api/auth、/api/me。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "顾客认证", description = "短信登录、刷新、资料")
public class CustomerAuthController {

    private final CustomerAuthService customerAuthService;
    private final CustomerEmailService customerEmailService;

    @PostMapping("/api/auth/sms/send")
    @Operation(summary = "发送验证码")
    public Result<Map<String, Object>> sendSms(@Valid @RequestBody SmsSendRequest req) {
        return Result.ok(customerAuthService.sendSms(req.getPhone()));
    }

    @PostMapping({"/api/auth/login", "/api/auth/phone"})
    @Operation(summary = "手机号验证码登录")
    public Result<CustomerLoginVO> login(@Valid @RequestBody CustomerLoginRequest req) {
        return Result.ok(customerAuthService.login(req));
    }

    @PostMapping("/api/auth/refresh")
    @Operation(summary = "刷新令牌")
    public Result<CustomerLoginVO> refresh(@Valid @RequestBody RefreshRequest req) {
        return Result.ok(customerAuthService.refresh(req.getRefreshToken()));
    }

    @PostMapping("/api/auth/email/send")
    @Operation(summary = "发送邮箱验证码（真发，随机 6 位，5 分钟有效）")
    public Result<?> sendEmailCode(@Valid @RequestBody EmailSendRequest req,
                                   jakarta.servlet.http.HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) {
            ip = request.getHeader("X-Forwarded-For");
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return Result.ok(customerEmailService.sendEmailCode(req.getEmail(), ip));
    }

    @PostMapping("/api/auth/login/email")
    @Operation(summary = "邮箱验证码登录（未注册自动注册）")
    public Result<CustomerLoginVO> loginByEmail(@Valid @RequestBody EmailLoginRequest req) {
        return Result.ok(customerEmailService.loginByEmail(req));
    }

    @PostMapping("/api/auth/logout")
    @Operation(summary = "退出登录")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        customerAuthService.logout(auth);
        return Result.ok();
    }

    @GetMapping("/api/me")
    @Operation(summary = "当前顾客资料")
    public Result<CustomerLoginVO.CustomerInfo> me() {
        return Result.ok(customerAuthService.me());
    }

    @PutMapping("/api/me")
    @Operation(summary = "更新资料")
    public Result<CustomerLoginVO.CustomerInfo> updateMe(@RequestBody Map<String, String> body) {
        return Result.ok(customerAuthService.updateMe(body.get("nickname"), body.get("avatar")));
    }
}
