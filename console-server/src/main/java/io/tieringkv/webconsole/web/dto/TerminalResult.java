package io.tieringkv.webconsole.web.dto;

/** 终端执行结果。 */
public record TerminalResult(String replyType, Object payload, long costMs, boolean error) {
}
