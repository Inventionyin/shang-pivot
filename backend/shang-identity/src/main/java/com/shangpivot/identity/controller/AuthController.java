package com.shangpivot.identity.controller;

import com.shangpivot.common.result.Result;
import com.shangpivot.identity.application.AuthService;
import com.shangpivot.identity.dto.LoginRequest;
import com.shangpivot.identity.dto.RefreshRequest;
import com.shangpivot.identity.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台认证 API：登录、登出、刷新令牌与当前用户信息。
 * <p>路径前缀 /admin/auth。
 */
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
@Tag(name = "认证授权", description = "登录、登出、刷新令牌与当前用户信息")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "使用用户名和密码登录，返回访问令牌与用户信息")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return Result.ok(authService.login(request, http.getRemoteAddr(), http.getHeader("User-Agent")));
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录", description = "使当前访问令牌失效")
    public Result<Void> logout(
            @Parameter(description = "Bearer 访问令牌", example = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
            @RequestHeader(value = "Authorization", required = false) String auth) {
        authService.logout(auth);
        return Result.ok();
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌", description = "使用刷新令牌获取新的访问令牌")
    public Result<LoginVO> refresh(@Valid @RequestBody RefreshRequest request) {
        return Result.ok(authService.refresh(request.getRefreshToken()));
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息", description = "返回当前登录用户的角色、权限与菜单")
    public Result<LoginVO.UserInfo> me() {
        return Result.ok(authService.me());
    }
}
