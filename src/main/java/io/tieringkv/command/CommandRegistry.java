package io.tieringkv.command;

import io.tieringkv.pubsub.PubSubBroker;
import io.tieringkv.observability.MultiModelMetricsRegistry;
import io.tieringkv.observability.VectorMetricsRegistry;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.vector.collection.VectorCollectionRegistry;
import io.tieringkv.vector.indexfile.VectorIndexStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 命令注册表：命令名（小写）→ 实现；启动时构建，运行期只读。 */
public final class CommandRegistry {

    private static final PubSubBroker DEFAULT_BROKER =
            ConnectionContext.sharedBroker();

    private final Map<String, Command> commands;

    private CommandRegistry(Map<String, Command> commands) {
        this.commands = Map.copyOf(commands);
    }

    public static CommandRegistry createDefault() {
        return createDefault(() -> "# Server\r\nno metrics\r\n");
    }

    public static CommandRegistry createDefault(Supplier<String> infoProvider) {
        return createDefault(infoProvider, Map.of());
    }

    public static CommandRegistry createDefault(
            Supplier<String> infoProvider,
            Map<String, Supplier<String>> sections) {
        return build(infoProvider, sections, List.of());
    }

    /**
     * 带向量命令族的注册表（ADR-0319）：默认注册表保持 115 命令不变，
     * 需要 VECTOR.ADD/SEARCH/DEL/LEN 时显式传入 VectorIndexStore。
     */
    public static CommandRegistry createDefaultWithVector(
            Supplier<String> infoProvider,
            Map<String, Supplier<String>> sections,
            VectorIndexStore vectorStore) {
        return createDefaultWithVector(infoProvider, sections,
                VectorCollectionRegistry.ofDefault(vectorStore));
    }

    /** 多集合向量注册表（ADR-0338）：集合命令 + LIST/DROP/CHECKPOINT。 */
    public static CommandRegistry createDefaultWithVector(
            Supplier<String> infoProvider,
            Map<String, Supplier<String>> sections,
            VectorCollectionRegistry registry) {
        return createDefaultWithVectorAndMetrics(
                infoProvider, sections, registry, null);
    }

    /** 多模型喂数（ADR-0345）：可选指标注册表（additive）。 */
    public static CommandRegistry createDefaultWithVectorAndMetrics(
            Supplier<String> infoProvider,
            Map<String, Supplier<String>> sections,
            VectorCollectionRegistry registry,
            MultiModelMetricsRegistry metrics) {
        return createDefaultWithVectorAndMetrics(
                infoProvider, sections, registry, metrics, null);
    }

    /** 向量 checkpoint 喂数（ADR-0344 收口）：可选向量指标（additive）。 */
    public static CommandRegistry createDefaultWithVectorAndMetrics(
            Supplier<String> infoProvider,
            Map<String, Supplier<String>> sections,
            VectorCollectionRegistry registry,
            MultiModelMetricsRegistry metrics,
            VectorMetricsRegistry vectorMetrics) {
        return build(infoProvider, sections, List.of(
                new VectorCommand("vector.add", registry, vectorMetrics),
                new VectorCommand("vector.search", registry, vectorMetrics),
                new VectorCommand("vector.del", registry, vectorMetrics),
                new VectorCommand("vector.len", registry, vectorMetrics),
                new VectorCommand("vector.list", registry, vectorMetrics),
                new VectorCommand("vector.drop", registry, vectorMetrics),
                new VectorCommand("vector.checkpoint", registry,
                        vectorMetrics),
                new JsonCommand("json.set", metrics),
                new JsonCommand("json.get", metrics),
                new JsonCommand("json.del", metrics),
                new JsonCommand("json.type", metrics),
                new JsonCommand("json.arrappend", metrics),
                new JsonCommand("json.arrlen", metrics),
                new JsonCommand("json.objkeys", metrics),
                new JsonCommand("json.objlen", metrics),
                new JsonCommand("json.strlen", metrics),
                new JsonCommand("json.numincrby", metrics),
                new MultiModelCommand("ts.add", metrics),
                new MultiModelCommand("ts.get", metrics),
                new MultiModelCommand("ts.len", metrics),
                new TimeSeriesCommand("ts.range", metrics),
                new TimeSeriesCommand("ts.mrange", metrics),
                new TimeSeriesCommand("ts.incrby", metrics),
                new TimeSeriesCommand("ts.reduce", metrics),
                new MultiModelCommand("vector.set", metrics),
                new MultiModelCommand("vector.get", metrics)));
    }

    private static CommandRegistry build(
            Supplier<String> infoProvider,
            Map<String, Supplier<String>> sections,
            List<Command> extra) {
        Map<String, Command> map = new HashMap<>();
        for (Command command : CommandCatalog.defaults()) {
            map.put(command.name(), command);
        }
        map.put("info", new InfoCommand(infoProvider, sections));
        for (Command command : extra) {
            map.put(command.name(), command);
        }
        map.put("exec", new ExecCommand(new CommandRegistry(map)));
        map.put("command", new CommandCommand(
                new CommandRegistry(map)));
        return new CommandRegistry(map);
    }

    public List<String> names() {
        return List.copyOf(commands.keySet());
    }

    public int size() {
        return commands.size();
    }

    public Command find(String name) {
        return commands.get(name);
    }
}
