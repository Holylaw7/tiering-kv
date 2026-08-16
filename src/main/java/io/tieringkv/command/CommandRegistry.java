package io.tieringkv.command;

import io.tieringkv.pubsub.PubSubBroker;
import io.tieringkv.observability.MultiModelMetricsRegistry;
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
        return build(infoProvider, sections, List.of(
                new VectorCommand("vector.add", registry),
                new VectorCommand("vector.search", registry),
                new VectorCommand("vector.del", registry),
                new VectorCommand("vector.len", registry),
                new VectorCommand("vector.list", registry),
                new VectorCommand("vector.drop", registry),
                new VectorCommand("vector.checkpoint", registry),
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
        for (Command command : List.of(
                new PingCommand(),
                new EchoCommand(),
                new SetCommand(),
                new GetCommand(),
                new DelCommand(),
                new ExistsCommand(),
                new InfoCommand(infoProvider, sections),
                new IncrCommand(),
                new DecrCommand(),
                new IncrByCommand(),
                new DecrByCommand(),
                new AppendCommand(),
                new StrlenCommand(),
                new GetSetCommand(),
                new SetNxCommand(),
                new SetExCommand("setex", 1000),
                new SetExCommand("psetex", 1),
                new GetDelCommand(),
                new GetRangeCommand(),
                new SetRangeCommand(),
                new TtlCommand("ttl"),
                new TtlCommand("pttl"),
                new ExpireCommand("expire", false, 1000),
                new ExpireCommand("pexpire", false, 1),
                new ExpireCommand("expireat", true, 1000),
                new ExpireCommand("pexpireat", true, 1),
                new PersistCommand(),
                new MgetCommand(),
                new MsetCommand(),
                new MsetNxCommand(),
                new DbsizeCommand(),
                new FlushCommand("flushdb"),
                new FlushCommand("flushall"),
                new ScanCommand(),
                new TypeCommand(),
                new ConfigCommand(),
                new ClientCommand(),
                new HashCommand("hset"),
                new HashCommand("hget"),
                new HashCommand("hdel"),
                new HashCommand("hexists"),
                new HashCommand("hlen"),
                new HashCommand("hkeys"),
                new HashCommand("hvals"),
                new HashCommand("hgetall"),
                new HashCommand("hmget"),
                new HashCommand("hmset"),
                new HashCommand("hincrby"),
                new HashCommand("hsetnx"),
                new ListCommand("lpush"),
                new ListCommand("rpush"),
                new ListCommand("lpop"),
                new ListCommand("rpop"),
                new ListCommand("llen"),
                new ListCommand("lrange"),
                new ListCommand("lindex"),
                new ListCommand("lset"),
                new ListCommand("lrem"),
                new ListCommand("ltrim"),
                new SetFamilyCommand("sadd"),
                new SetFamilyCommand("srem"),
                new SetFamilyCommand("sismember"),
                new SetFamilyCommand("scard"),
                new SetFamilyCommand("smembers"),
                new SetFamilyCommand("spop"),
                new SetFamilyCommand("srandmember"),
                new SetFamilyCommand("sinter"),
                new SetFamilyCommand("sunion"),
                new SetFamilyCommand("sdiff"),
                new SetFamilyCommand("sinterstore"),
                new SetFamilyCommand("sunionstore"),
                new SetFamilyCommand("sdiffstore"),
                new ZSetCommand("zadd"),
                new ZSetCommand("zscore"),
                new ZSetCommand("zrange"),
                new ZSetCommand("zrevrange"),
                new ZSetCommand("zrem"),
                new ZSetCommand("zcard"),
                new ZSetCommand("zincrby"),
                new ZSetCommand("zrangebyscore"),
                new ZSetCommand("zcount"),
                new ZSetCommand("zrank"),
                new ZSetCommand("zrevrank"),
                new HelloCommand(),
                new ObjectCommand(),
                new AclCommand(),
                new ScriptCommand("script"),
                new ScriptCommand("eval"),
                new ScriptCommand("evalsha"),
                new PubSubCommand("publish", DEFAULT_BROKER),
                new PubSubCommand("subscribe", DEFAULT_BROKER),
                new PubSubCommand("unsubscribe", DEFAULT_BROKER),
                new PubSubCommand("psubscribe", DEFAULT_BROKER),
                new PubSubCommand("punsubscribe", DEFAULT_BROKER),
                new MultiCommand(),
                new DiscardCommand(),
                new WatchCommand(),
                new HashCommand("hscan"),
                new ListCommand("linsert"),
                new ListCommand("lmove"),
                new ListCommand("rpoplpush"),
                new ZSetCommand("zrangebylex"),
                new ZSetCommand("zlexcount"),
                new ZSetCommand("zremrangebylex"),
                new StreamCommand("xadd"),
                new StreamCommand("xlen"),
                new StreamCommand("xrange"),
                new StreamCommand("xtrim"),
                new StreamCommand("xread"),
                new StreamCommand("xgroup"),
                new StreamCommand("xreadgroup"),
                new StreamCommand("xack"),
                new StreamCommand("xpending"),
                new StreamCommand("xclaim"),
                new StreamCommand("xautoclaim"),
                new BitCommand("setbit"),
                new BitCommand("getbit"),
                new BitCommand("bitcount"),
                new BitCommand("bitpos"),
                new BitCommand("bitop"),
                new GeoCommand("geoadd"),
                new GeoCommand("geopos"),
                new GeoCommand("geodist"),
                new GeoCommand("geohash"),
                new GeoCommand("geosearch"),
                new GeoCommand("georadius"),
                new GeoCommand("georadiusbymember"),
                new ListCommand("blpop"),
                new ListCommand("brpop"),
                new UnwatchCommand())) {
            map.put(command.name(), command);
        }
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
