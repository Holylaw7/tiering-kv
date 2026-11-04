package io.tieringkv.webconsole.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** 从打包产物读取构建版本（测试环境下退化为 "dev"）。 */
final class ProjectVersion {

    private ProjectVersion() {
    }

    static String resolve() {
        try (InputStream in = ProjectVersion.class.getResourceAsStream(
                "/META-INF/maven/io.tieringkv/tiering-kv-console/pom.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "dev");
        } catch (IOException e) {
            return "dev";
        }
    }
}
