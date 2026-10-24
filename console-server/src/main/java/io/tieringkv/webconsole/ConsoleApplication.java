package io.tieringkv.webconsole;

import io.tieringkv.webconsole.config.ConsoleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Tiering-KV Web 运维管理平台后端入口（控制面）。 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ConsoleProperties.class)
public class ConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsoleApplication.class, args);
    }
}
