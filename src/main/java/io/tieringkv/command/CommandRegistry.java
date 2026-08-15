package io.tieringkv.command;

import io.tieringkv.pubsub.PubSubBroker;
import io.tieringkv.session.ConnectionContext;
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
        return build(infoProvider, sections, List.of(
                new VectorCommand("vector.add", vectorStore),
                new VectorCommand("vector.search", vectorStore),
                new VectorCommand("vector.del", vectorStore),
                new VectorCommand("vector.len", vectorStore),
                new MultiModelCommand("json.set"),
                new MultiModelCommand("json.get"),
                new MultiModelCommand("ts.add"),
                new MultiModelCommand("ts.get"),
                new MultiModelCommand("ts.len"),
                new MultiModelCommand("vector.set"),
                new MultiModelCommand("vector.get")));
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
