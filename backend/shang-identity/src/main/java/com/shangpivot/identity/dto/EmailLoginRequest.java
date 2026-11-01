package com.shangpivot.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "邮箱验证码登录请求")
public class EmailLoginRequest {

    @Schema(description = "邮箱", example = "user@example.com")
    @NotBlank(message = "请输入邮箱")
    private String email;

    @Schema(description = "6 位验证码", example = "123456")
    @NotBlank(message = "请输入验证码")
    private String code;

    @Schema(description = "客户端类型 web/h5")
    private String clientType;
}
