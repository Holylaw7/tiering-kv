package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.support.Text;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 慢查询日志只读查询与清空服务（N2-A）。
 *
 * <p>实时按需查询数据面 `SLOWLOG LEN` 与 `SLOWLOG GET`，不额外写 MySQL；
 * 若被纳管实例未启用或不支持 `SLOWLOG`，优雅降级为 {@code supported=false}。
 */
@Service
public class SlowLogService {

    private final InstanceService instanceService;
    private final RespGateway gateway;
    private final AuditService auditService;

    public SlowLogService(InstanceService instanceService, RespGateway gateway, AuditService auditService) {
        this.instanceService = instanceService;
        this.gateway = gateway;
        this.auditService = auditService;
    }

    public record SlowLogEntryView(long id, long timestampSeconds, long durationMicros,
                                   String command, List<String> args) {
    }

    public record SlowLogView(boolean supported, long totalLen, List<SlowLogEntryView> entries,
                              String message) {
    }

    public SlowLogView list(Long instanceId, int limit) {
        if (limit < 1 || limit > 256) {
            throw BusinessException.badRequest("limit 必须在 1..256 之间");
        }
        DbInstance instance = instanceService.require(instanceId);
        try {
            RespReply lenReply = gateway.execute(instance, "SLOWLOG", "LEN");
            if (lenReply instanceof RespReply.Error err) {
                String msg = err.message() == null ? "" : err.message();
                if (isUnsupportedError(msg)) {
                    return new SlowLogView(false, 0L, List.of(), "实例不支持 SLOWLOG 命令：" + msg);
                }
                throw BusinessException.upstream("查询 SLOWLOG LEN 失败：" + msg);
            }
            long totalLen = lenReply instanceof RespReply.Integer integer ? integer.value() : 0L;

            RespReply getReply = gateway.execute(instance, "SLOWLOG", "GET", String.valueOf(limit));
            if (getReply instanceof RespReply.Error err) {
                String msg = err.message() == null ? "" : err.message();
                if (isUnsupportedError(msg)) {
                    return new SlowLogView(false, 0L, List.of(), "实例不支持 SLOWLOG 命令：" + msg);
                }
                throw BusinessException.upstream("查询 SLOWLOG GET 失败：" + msg);
            }
            List<SlowLogEntryView> entries = parseEntries(getReply);
            return new SlowLogView(true, totalLen, entries, null);
        } catch (IOException ex) {
            throw BusinessException.upstream("读取 SLOWLOG 失败：" + ex.getMessage());
        }
    }

    public void reset(Long instanceId) {
        DbInstance instance = instanceService.require(instanceId);
        try {
            RespReply reply = gateway.execute(instance, "SLOWLOG", "RESET");
            if (reply instanceof RespReply.Error err) {
                String msg = err.message() == null ? "" : err.message();
                auditService.record("SLOWLOG_RESET", instance.getName(), truncate(msg), false);
                if (isUnsupportedError(msg)) {
                    throw BusinessException.badRequest("当前实例不支持 SLOWLOG 命令：" + msg);
                }
                throw BusinessException.upstream("清空 SLOWLOG 失败：" + msg);
            }
            auditService.record("SLOWLOG_RESET", instance.getName(), "OK", true);
        } catch (IOException ex) {
            auditService.record("SLOWLOG_RESET", instance.getName(), truncate(ex.getMessage()), false);
            throw BusinessException.upstream("清空 SLOWLOG 失败：" + ex.getMessage());
        }
    }

    private static List<SlowLogEntryView> parseEntries(RespReply reply) {
        if (!(reply instanceof RespReply.Array rows) || rows.isNil()) {
            return List.of();
        }
        List<SlowLogEntryView> out = new ArrayList<>(rows.items().size());
        for (RespReply row : rows.items()) {
            if (!(row instanceof RespReply.Array tuple) || tuple.items().size() < 4) {
                continue;
            }
            long id = asLong(tuple.items().get(0));
            long ts = asLong(tuple.items().get(1));
            long micros = asLong(tuple.items().get(2));
            List<String> args = decodeArgs(tuple.items().get(3));
            String command = args.isEmpty() ? "" : args.get(0).toUpperCase(Locale.ROOT);
            out.add(new SlowLogEntryView(id, ts, micros, command, args));
        }
        return List.copyOf(out);
    }

    private static List<String> decodeArgs(RespReply argReply) {
        if (!(argReply instanceof RespReply.Array arr) || arr.isNil()) {
            return List.of();
        }
        List<String> args = new ArrayList<>(arr.items().size());
        for (RespReply item : arr.items()) {
            args.add(switch (item) {
                case RespReply.Bulk bulk -> bulk.isNil() ? "" : Text.decode(bulk.value());
                case RespReply.Simple simple -> simple.value();
                case RespReply.Integer integer -> String.valueOf(integer.value());
                default -> "";
            });
        }
        return List.copyOf(args);
    }

    private static long asLong(RespReply reply) {
        return reply instanceof RespReply.Integer integer ? integer.value() : 0L;
    }

    private static boolean isUnsupportedError(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("unknown command") || lower.contains("unknown subcommand")
                || lower.contains("not supported");
    }

    private static String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() > 240 ? msg.substring(0, 240) : msg;
    }
}
