package io.tieringkv.webconsole.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 契约配置（TD-088）。
 *
 * <p>`/v3/api-docs` 是前后端契约的唯一来源：
 * <ul>
 *   <li>后端：`OpenApiSnapshotTest` 与 `docs/api/openapi.json` 比对，防止契约漂移；</li>
 *   <li>前端：`npm run gen:api` 由该文件生成 `src/api/schema.d.ts`。</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI consoleOpenApi() {
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("登录后获得的 JWT，放在 Authorization: Bearer <token>");
        return new OpenAPI()
                .info(new Info()
                        .title("Tiering-KV 控制面 API")
                        .version(ProjectVersion.resolve())
                        .description("实例管理、指标、键空间、终端、告警、备份、审计、用户；"
                                + "统一响应：{ code, message, data }；分页：{ items, total, page, size }。"))
                .components(new Components().addSecuritySchemes("bearerAuth", bearer))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
