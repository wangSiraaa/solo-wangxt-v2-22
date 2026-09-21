package com.park.energy.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 所有 BigDecimal 以 JSON 字符串输出（如 "32.53"）。
 * 金额/电量必须避免 JavaScript number 的浮点失真，前端按字符串接收再用定点库解析。
 */
@Configuration
public class JacksonConfig {

    static class BigDecimalAsStringSerializer extends JsonSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value == null ? null : value.toPlainString());
        }
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalAsString() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(BigDecimal.class, new BigDecimalAsStringSerializer());
            builder.modulesToInstall(module);
        };
    }
}
