package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.SstableTable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * SSTable 清单（TD-099）：按需读取 `INFO sstables`。
 *
 * <p>该段落只包含冷层表的元数据（id / 键数 / 字节 / 键范围），
 * 不进入全量 INFO，避免表多时把 INFO 撑大。
 */
@Service
public class TieringTableService {

    private final InstanceService instanceService;
    private final RespGateway gateway;

    public TieringTableService(InstanceService instanceService, RespGateway gateway) {
        this.instanceService = instanceService;
        this.gateway = gateway;
    }

    public SstableTable.View list(Long instanceId) {
        DbInstance instance = instanceService.require(instanceId);
        String text;
        try {
            RespReply reply = gateway.execute(instance, "INFO", "sstables");
            if (reply instanceof RespReply.Error error) {
                throw BusinessException.upstream("INFO sstables 失败：" + error.message());
            }
            text = reply instanceof RespReply.Bulk bulk ? bulk.utf8() : null;
        } catch (IOException ex) {
            throw BusinessException.upstream("读取 SSTable 清单失败：" + ex.getMessage());
        }
        if (text == null || text.isBlank()) {
            throw BusinessException.upstream("内核未返回 SSTable 段落（版本过旧？）");
        }

        List<SstableTable> tables = new ArrayList<>();
        long totalBytes = 0;
        long totalEntries = 0;
        String keyOrder = "unknown";
        for (String rawLine : text.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.startsWith("sstable_key_order:")) {
                keyOrder = line.substring("sstable_key_order:".length()).trim();
            } else if (line.startsWith("sstable_total_bytes:")) {
                totalBytes = parseLong(line.substring("sstable_total_bytes:".length()));
            } else if (line.startsWith("sstable_total_entries:")) {
                totalEntries = parseLong(line.substring("sstable_total_entries:".length()));
            } else if (line.startsWith("sstable:") && line.contains("|keys=")) {
                tables.add(parseRow(line));
            }
        }
        return new SstableTable.View(keyOrder, tables.size(), totalBytes, totalEntries, tables);
    }

    private static SstableTable parseRow(String line) {
        // 形如 sstable:3|keys=100|bytes=2048|first_b64=...|last_b64=...
        String[] parts = line.split("\\|");
        long id = parseLong(parts[0].substring("sstable:".length()));
        long keys = 0;
        long bytes = 0;
        String first = "";
        String last = "";
        String firstB64 = "";
        String lastB64 = "";
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = part.substring(0, eq);
            String value = part.substring(eq + 1);
            switch (name) {
                case "keys" -> keys = parseLong(value);
                case "bytes" -> bytes = parseLong(value);
                case "first_b64" -> firstB64 = value;
                case "last_b64" -> lastB64 = value;
                case "first_text" -> first = value;
                case "last_text" -> last = value;
                default -> {
                }
            }
        }
        // 兼容没有 *_text 字段的旧内核：回退到 Base64 解码
        if (first.isEmpty() && !firstB64.isEmpty()) {
            first = decode(firstB64);
        }
        if (last.isEmpty() && !lastB64.isEmpty()) {
            last = decode(lastB64);
        }
        return new SstableTable(id, keys, bytes, first, last, firstB64, lastB64);
    }

    private static String decode(String base64) {
        if (base64 == null || base64.isBlank()) {
            return "";
        }
        try {
            String text = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            return text.length() > 80 ? text.substring(0, 80) + "…" : text;
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}