package io.tieringkv.webconsole.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalTokenizerTest {

    @Test
    void splitsOnWhitespace() {
        assertThat(TerminalService.tokenize("SET k v")).containsExactly("SET", "k", "v");
    }

    @Test
    void collapsesRepeatedSpaces() {
        assertThat(TerminalService.tokenize("  GET    k  ")).containsExactly("GET", "k");
    }

    @Test
    void keepsQuotedSegmentsTogether() {
        assertThat(TerminalService.tokenize("SET k \"hello world\""))
                .containsExactly("SET", "k", "hello world");
        assertThat(TerminalService.tokenize("SET k 'a b c'"))
                .containsExactly("SET", "k", "a b c");
    }

    @Test
    void supportsEscapedQuoteInsideDoubleQuotes() {
        assertThat(TerminalService.tokenize("SET k \"a\\\"b\""))
                .containsExactly("SET", "k", "a\"b");
    }

    @Test
    void handlesEmptyInput() {
        assertThat(TerminalService.tokenize("")).isEmpty();
        assertThat(TerminalService.tokenize(null)).isEmpty();
        assertThat(TerminalService.tokenize("   ")).isEmpty();
    }
}
