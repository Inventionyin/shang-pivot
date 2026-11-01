package com.shangpivot.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "邮箱验证码发送请求")
public class EmailSendRequest {

    @Schema(description = "邮箱", example = "user@example.com")
    @NotBlank(message = "请输入邮箱")
    private String email;
}
