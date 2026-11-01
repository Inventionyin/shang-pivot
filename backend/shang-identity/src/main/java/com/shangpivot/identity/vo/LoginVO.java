package com.shangpivot.identity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
@Builder
@Schema(description = "登录响应")
public class LoginVO {
    @Schema(description = "访问令牌", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
    private String accessToken;
    @Schema(description = "刷新令牌", example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
    private String refreshToken;
    @Schema(description = "访问令牌过期时间（秒）", example = "7200")
    private Long expiresIn;
    @Schema(description = "当前用户信息")
    private UserInfo user;

    @Data
    @Builder
    @Schema(description = "当前用户信息")
    public static class UserInfo {
        @Schema(description = "用户ID", example = "1")
        private Long id;
        @Schema(description = "用户名", example = "admin")
        private String username;
        @Schema(description = "真实姓名", example = "管理员")
        private String realName;
        @Schema(description = "角色编码集合", example = "[\"ADMIN\"]")
        private Set<String> roles;
        @Schema(description = "权限编码集合", example = "[\"user:list\", \"user:create\"]")
        private Set<String> permissions;
        @Schema(description = "菜单树")
        private List<MenuTreeVO> menus;
    }
}
