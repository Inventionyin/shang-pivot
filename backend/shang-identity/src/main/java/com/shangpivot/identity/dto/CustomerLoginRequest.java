package com.shangpivot.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "顾客手机号登录请求")
public class CustomerLoginRequest {
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    @Schema(description = "手机号", example = "13800138000")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    @Schema(description = "验证码", example = "123456")
    private String code;

    @NotBlank(message = "clientType 不能为空")
    @Pattern(regexp = "^(?i)(web|h5)$", message = "clientType 须为 web 或 h5")
    @Schema(description = "客户端类型", example = "h5", allowableValues = {"web", "h5"})
    private String clientType;
}
