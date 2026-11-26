package io.tieringkv.webconsole.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 统一时间序列化格式：yyyy-MM-dd HH:mm:ss（Jackson 3 / Spring Boot 4）。 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public JsonMapperBuilderCustomizer localDateTimeCustomizer() {
        return builder -> {
            SimpleModule javaTimeModule = new SimpleModule("ConsoleLocalDateTimeModule");
            javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(FORMATTER));
            javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(FORMATTER));
            builder.addModule(javaTimeModule)
                    .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
        };
    }
}
