package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sBackupTriggerResult;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sClusterListView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sClusterSummary;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sScaleRequest;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sUpgradeRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Kubernetes Operator 零依赖桥接服务（v6.2.0 C2）：
 * 严格使用纯 JDK 21 {@link HttpClient} + Jackson 3 与 Kubernetes CustomObjects API
 * ({@code tieringkv.io/v1/tieringkvclusters}) 通信，零 Fabric8 / Jackson 2 传递依赖；
 * 未启用或未接入集群时优雅降级返回 {@code enabled=false}。
 */
@Service
public class K8sOperatorBridgeService {

    private static final String CRD_GROUP = "tieringkv.io";
    private static final String CRD_VERSION = "v1";
    private static final String CRD_PLURAL = "tieringkvclusters";
    private static final String BACKUP_ANNOTATION = "tieringkv.io/backup-request-id";
    private static final Pattern K8S_NAME_PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    private final ConsoleProperties properties;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public K8sOperatorBridgeService(ConsoleProperties properties,
                                    ObjectMapper objectMapper,
                                    AuditService auditService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public K8sClusterListView listClusters() {
        ConsoleProperties.Kubernetes cfg = properties.getKubernetes();
        if (!cfg.isEnabled()) {
            return new K8sClusterListView(
                    false, cfg.getNamespace(), cfg.getApiServer(),
                    "未启用 Kubernetes Operator 桥接（设置 CONSOLE_K8S_ENABLED=true 接入集群）",
                    List.of());
        }
        String token = resolveToken(cfg);
        if (token == null || token.isBlank()) {
            return new K8sClusterListView(
                    false, cfg.getNamespace(), cfg.getApiServer(),
                    "未检测到 Kubernetes ServiceAccount 令牌，请配置 CONSOLE_K8S_BEARER_TOKEN 或挂载集群 ServiceAccount",
                    List.of());
        }
        String url = collectionUrl(cfg);
        JsonNode root = sendJsonRequest(cfg, token, "GET", url, null);
        List<K8sClusterSummary> clusters = new ArrayList<>();
        JsonNode items = root.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                clusters.add(parseCluster(item, cfg.getNamespace()));
            }
        }
        return new K8sClusterListView(true, cfg.getNamespace(), cfg.getApiServer(), null, clusters);
    }

    public K8sClusterSummary scaleCluster(String name, K8sScaleRequest request) {
        ConsoleProperties.Kubernetes cfg = requireEnabled();
        String token = requireToken(cfg);
        String validName = validateName(name);
        Map<String, Object> specPatch = new LinkedHashMap<>();
        if (request.storageReplicas() != null) {
            specPatch.put("storageReplicas", request.storageReplicas());
        }
        if (request.gatewayReplicas() != null) {
            specPatch.put("gatewayReplicas", request.gatewayReplicas());
        }
        if (request.metadataReplicas() != null) {
            specPatch.put("metadataReplicas", request.metadataReplicas());
        }
        if (specPatch.isEmpty()) {
            throw BusinessException.badRequest("请至少指定一项需要调整的副本数（storage / gateway / metadata）");
        }
        Map<String, Object> patch = Map.of("spec", specPatch);
        JsonNode updated = sendJsonRequest(cfg, token, "PATCH", resourceUrl(cfg, validName), patch);
        auditService.record("K8S_CLUSTER_SCALE", cfg.getNamespace() + "/" + validName, specPatch.toString(), true);
        return parseCluster(updated, cfg.getNamespace());
    }

    public K8sClusterSummary upgradeCluster(String name, K8sUpgradeRequest request) {
        ConsoleProperties.Kubernetes cfg = requireEnabled();
        String token = requireToken(cfg);
        String validName = validateName(name);
        String image = request.image().trim();
        if (image.chars().anyMatch(ch -> Character.isWhitespace(ch) || ch < 32)) {
            throw BusinessException.badRequest("目标镜像地址包含非法空白或控制字符");
        }
        Map<String, Object> patch = Map.of("spec", Map.of("image", image));
        JsonNode updated = sendJsonRequest(cfg, token, "PATCH", resourceUrl(cfg, validName), patch);
        auditService.record("K8S_CLUSTER_UPGRADE", cfg.getNamespace() + "/" + validName, "image=" + image, true);
        return parseCluster(updated, cfg.getNamespace());
    }

    public K8sBackupTriggerResult triggerBackup(String name) {
        ConsoleProperties.Kubernetes cfg = requireEnabled();
        String token = requireToken(cfg);
        String validName = validateName(name);
        String requestId = "console-" + Instant.now().getEpochSecond();
        Map<String, Object> patch = Map.of(
                "metadata", Map.of("annotations", Map.of(BACKUP_ANNOTATION, requestId)));
        sendJsonRequest(cfg, token, "PATCH", resourceUrl(cfg, validName), patch);
        auditService.record("K8S_CLUSTER_BACKUP", cfg.getNamespace() + "/" + validName,
                "requestId=" + requestId, true);
        return new K8sBackupTriggerResult(
                validName, cfg.getNamespace(), requestId,
                "已通过 CR 注解触发按需备份 Job（" + validName + "-backup-" + requestId + "）");
    }

    private ConsoleProperties.Kubernetes requireEnabled() {
        ConsoleProperties.Kubernetes cfg = properties.getKubernetes();
        if (!cfg.isEnabled()) {
            throw BusinessException.badRequest("未启用 Kubernetes Operator 桥接（CONSOLE_K8S_ENABLED=false）");
        }
        return cfg;
    }

    private String requireToken(ConsoleProperties.Kubernetes cfg) {
        String token = resolveToken(cfg);
        if (token == null || token.isBlank()) {
            throw BusinessException.badRequest("缺少 Kubernetes API Bearer 令牌");
        }
        return token;
    }

    private static String resolveToken(ConsoleProperties.Kubernetes cfg) {
        if (cfg.getBearerToken() != null && !cfg.getBearerToken().isBlank()) {
            return cfg.getBearerToken().trim();
        }
        if (cfg.getTokenPath() == null || cfg.getTokenPath().isBlank()) {
            return null;
        }
        Path path = Path.of(cfg.getTokenPath());
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            return null;
        }
    }

    private static String validateName(String name) {
        if (name == null || !K8S_NAME_PATTERN.matcher(name.trim()).matches()) {
            throw BusinessException.badRequest("非法的 Kubernetes 集群资源名称：" + name);
        }
        return name.trim();
    }

    private static String collectionUrl(ConsoleProperties.Kubernetes cfg) {
        String base = cfg.getApiServer().replaceAll("/+$", "");
        String ns = validateName(cfg.getNamespace());
        return base + "/apis/" + CRD_GROUP + "/" + CRD_VERSION + "/namespaces/" + ns + "/" + CRD_PLURAL;
    }

    private static String resourceUrl(ConsoleProperties.Kubernetes cfg, String name) {
        return collectionUrl(cfg) + "/" + name;
    }

    private JsonNode sendJsonRequest(ConsoleProperties.Kubernetes cfg, String token,
                                     String method, String url, Object body) {
        HttpClient client = buildClient(cfg);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000, cfg.getRequestTimeoutMs())))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if ("PATCH".equals(method)) {
            String json = objectMapper.writeValueAsString(body);
            builder.header("Content-Type", "application/merge-patch+json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code == 404) {
                throw BusinessException.notFound("Kubernetes TieringKVCluster 资源不存在");
            }
            if (code == 401 || code == 403) {
                throw BusinessException.upstream("Kubernetes API 鉴权失败（HTTP " + code + "），请检查 RBAC 权限");
            }
            if (code < 200 || code >= 300) {
                throw BusinessException.upstream("Kubernetes API 返回异常（HTTP " + code + "）：" + truncate(response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw BusinessException.upstream("调用 Kubernetes API 被中断");
        } catch (IOException | RuntimeException ex) {
            throw BusinessException.upstream("连接 Kubernetes API 失败：" + ex.getMessage());
        }
    }

    private static HttpClient buildClient(ConsoleProperties.Kubernetes cfg) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, cfg.getConnectTimeoutMs())));
        if (cfg.getApiServer().startsWith("https://")
                && cfg.getCaCertPath() != null && !cfg.getCaCertPath().isBlank()) {
            Path caPath = Path.of(cfg.getCaCertPath());
            if (Files.isRegularFile(caPath)) {
                builder.sslContext(buildSslContext(caPath));
            }
        }
        return builder.build();
    }

    private static SSLContext buildSslContext(Path caPath) {
        try {
            byte[] pem = Files.readAllBytes(caPath);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(new ByteArrayInputStream(pem));
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int idx = 0;
            for (Certificate cert : certs) {
                ks.setCertificateEntry("k8s-ca-" + idx++, cert);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            return ssl;
        } catch (Exception ex) {
            throw BusinessException.upstream("加载 Kubernetes CA 证书失败：" + ex.getMessage());
        }
    }

    private static K8sClusterSummary parseCluster(JsonNode root, String fallbackNamespace) {
        JsonNode meta = root.path("metadata");
        JsonNode spec = root.path("spec");
        JsonNode status = root.path("status");
        List<String> regionIds = new ArrayList<>();
        JsonNode regionsNode = spec.path("regionIds");
        if (regionsNode.isArray()) {
            for (JsonNode r : regionsNode) {
                if (r.isString()) {
                    regionIds.add(r.asString());
                }
            }
        }
        return new K8sClusterSummary(
                textOrNull(meta, "name"),
                firstNonBlank(textOrNull(meta, "namespace"), fallbackNamespace),
                firstNonBlank(textOrNull(status, "phase"), "PENDING"),
                textOrNull(spec, "image"),
                spec.path("metadataReplicas").asInt(1),
                status.path("readyMetadata").asInt(0),
                spec.path("storageReplicas").asInt(1),
                status.path("readyStorage").asInt(0),
                spec.path("gatewayReplicas").asInt(1),
                status.path("readyGateway").asInt(0),
                regionIds,
                meta.has("generation") ? meta.path("generation").asLong() : null,
                status.has("observedGeneration") ? status.path("observedGeneration").asLong() : null,
                textOrNull(status, "lastAction"),
                textOrNull(status, "lastBackupJob"),
                textOrNull(status, "lastBackupStatus"),
                textOrNull(status, "lastBackupTime"),
                textOrNull(spec, "backupScheduleCron"),
                textOrNull(status, "message"),
                textOrNull(meta, "creationTimestamp"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child == null || child.isNull()) ? null : child.asString();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
