package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.common.PageResult;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.TerminalHistory;
import io.tieringkv.webconsole.repository.TerminalHistoryRepository;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.security.SecurityUtils;
import io.tieringkv.webconsole.security.UserPrincipal;
import io.tieringkv.webconsole.support.Text;
import io.tieringkv.webconsole.web.dto.TerminalResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 在线终端：命令解析、危险命令拦截、执行留痕。 */
@Service
public class TerminalService {

    private final InstanceService instanceService;
    private final RespGateway gateway;
    private final TerminalHistoryRepository historyRepository;
    private final ConsoleProperties properties;
    private final AuditService auditService;

    public TerminalService(InstanceService instanceService, RespGateway gateway,
                           TerminalHistoryRepository historyRepository,
                           ConsoleProperties properties, AuditService auditService) {
        this.instanceService = instanceService;
        this.gateway = gateway;
        this.historyRepository = historyRepository;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional
    public TerminalResult execute(Long instanceId, String rawCommand) {
        DbInstance instance = instanceService.require(instanceId);
        List<String> args = tokenize(rawCommand);
        if (args.isEmpty()) {
            throw BusinessException.badRequest("命令不能为空");
        }
        String head = args.get(0).toUpperCase(Locale.ROOT);
        List<String> blocked = properties.getTerminal().getBlockedCommands().stream()
                .map(cmd -> cmd.trim().toUpperCase(Locale.ROOT)).toList();
        if (blocked.contains(head)) {
            auditService.record("TERMINAL_BLOCKED", instance.getName() + "/" + head,
                    "命令被策略拦截", false);
            throw BusinessException.badRequest("该命令已被平台策略禁用：" + head);
        }
        long started = System.nanoTime();
        RespReply reply;
        try {
            reply = gateway.execute(instance, args.toArray(new String[0]));
        } catch (IOException ex) {
            record(instance, rawCommand, false, elapsed(started), "io_error");
            throw BusinessException.upstream("实例通信失败：" + ex.getMessage());
        }
        long costMs = elapsed(started);
        boolean error = reply instanceof RespReply.Error;
        record(instance, rawCommand, !error, costMs, RespReply.typeName(reply));
        return new TerminalResult(RespReply.typeName(reply), toPayload(reply), costMs, error);
    }

    public PageResult<TerminalHistory> history(int page, int size) {
        PageRequest request = PageRequest.of(Math.max(0, page - 1), Math.min(200, Math.max(1, size)));
        Page<TerminalHistory> result = historyRepository.findAllByOrderByCreatedAtDesc(request);
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    private void record(DbInstance instance, String command, boolean success,
                        long costMs, String replyType) {
        TerminalHistory history = new TerminalHistory();
        try {
            UserPrincipal principal = SecurityUtils.currentUser();
            history.setUserId(principal.id());
            history.setUsername(principal.username());
        } catch (RuntimeException ignored) {
            history.setUsername("anonymous");
        }
        history.setInstanceId(instance.getId());
        history.setCommand(command.length() > 500 ? command.substring(0, 500) : command);
        history.setSuccess(success);
        history.setCostMs(costMs);
        history.setReplyType(replyType);
        history.setCreatedAt(LocalDateTime.now());
        historyRepository.save(history);
    }

    private static long elapsed(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    /** 解析命令行：支持单/双引号包裹的分段，连续空白折叠。 */
    public static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        if (line == null) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean started = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < line.length()) {
                    current.append(line.charAt(++i));
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                started = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                started = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
                continue;
            }
            current.append(c);
            started = true;
        }
        if (started) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private Object toPayload(RespReply reply) {
        return switch (reply) {
            case RespReply.Simple simple -> simple.value();
            case RespReply.Error error -> error.message();
            case RespReply.Integer integer -> integer.value();
            case RespReply.Bulk bulk -> bulk.isNil() ? null : Text.decode(bulk.value());
            case RespReply.Array array -> {
                if (array.isNil()) {
                    yield null;
                }
                List<Object> items = new ArrayList<>(array.items().size());
                array.items().forEach(item -> items.add(toPayload(item)));
                yield items;
            }
        };
    }

    /** 供前端展示：复杂应答转成带类型说明的 Map。 */
    public Map<String, Object> describe(TerminalResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("replyType", result.replyType());
        map.put("payload", result.payload());
        map.put("costMs", result.costMs());
        map.put("error", result.error());
        return map;
    }
}
