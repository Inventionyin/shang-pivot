package com.shangpivot.common.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 雪花 Long ID 超出 JS Number 安全整数（2^53-1）时会丢精度，
 * 导致前端用错误 id 请求详情出现「资源不存在」。统一按字符串输出。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer longAsStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("LongAsStringModule");
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.addModule(module);
        };
    }
}
