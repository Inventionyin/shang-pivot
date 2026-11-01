package com.shangpivot.identity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "顾客登录响应")
public class CustomerLoginVO {
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;
    private CustomerInfo customer;

    @Data
    @Builder
    @Schema(description = "顾客资料")
    public static class CustomerInfo {
        private Long id;
        private String phone;
        private String email;
        private String nickname;
        private String avatar;
        private String status;
    }
}
