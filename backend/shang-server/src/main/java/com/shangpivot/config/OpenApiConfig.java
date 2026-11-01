package com.shangpivot.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI shangPivotOpenApi() {
        final String scheme = "BearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("Shang Pivot 管理端 API")
                        .description("""
                                Phase 1 后台管理接口文档。
                                除登录/刷新外，请在右上角 Authorize 填入：Bearer {accessToken}
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("Shang Pivot")))
                .addSecurityItem(new SecurityRequirement().addList(scheme))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme()
                                .name(scheme)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 accessToken，格式：Bearer xxx")));
    }
}
