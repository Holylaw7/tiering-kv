package io.tieringkv.webconsole.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约漂移校验（TD-088）：`/v3/api-docs` 必须与入库的
 * `docs/api/openapi.json` 一致。
 *
 * <p>接口改动后运行 `mvn test -Dopenapi.update=true` 重新生成快照，
 * 再执行 `npm run gen:api` 同步前端类型。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiSnapshotTest {

    private static final Path SNAPSHOT = Path.of("..", "docs", "api", "openapi.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void openApiMatchesCommittedSnapshot() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String actual = normalize(body);

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(SNAPSHOT.getParent());
            Files.writeString(SNAPSHOT, actual + System.lineSeparator());
            return;
        }

        assertThat(Files.exists(SNAPSHOT))
                .as("缺少契约快照，请运行 mvn test -Dopenapi.update=true 生成")
                .isTrue();
        String expected = normalize(Files.readString(SNAPSHOT));
        assertThat(actual)
                .as("API 契约与 docs/api/openapi.json 不一致：若为有意改动，"
                        + "请运行 mvn test -Dopenapi.update=true 并同步前端类型")
                .isEqualTo(expected);
    }

    /** 稳定化：剔除构建期易变字段，键按字典序输出。 */
    private String normalize(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (root instanceof ObjectNode object && object.has("info")) {
            ((ObjectNode) object.get("info")).remove("version");
        }
        sortRecursively(root);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private void sortRecursively(JsonNode node) {
        if (node instanceof ObjectNode object) {
            // 键值成对取出后按 key 排序，避免"名字排序、值不排序"造成错位
            java.util.List<java.util.Map.Entry<String, JsonNode>> entries = new java.util.ArrayList<>();
            object.fields().forEachRemaining(entry ->
                    entries.add(java.util.Map.entry(entry.getKey(), entry.getValue())));
            entries.sort(java.util.Map.Entry.comparingByKey());
            object.removeAll();
            for (var entry : entries) {
                sortRecursively(entry.getValue());
                object.set(entry.getKey(), entry.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(this::sortRecursively);
        }
    }
}
