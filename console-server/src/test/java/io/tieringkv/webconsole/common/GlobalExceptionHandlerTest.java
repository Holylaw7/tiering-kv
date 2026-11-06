package io.tieringkv.webconsole.common;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 11 P1：数据库不可用应返回 503，而不是 500；且不泄漏底层细节。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/auth/login");
    }

    @Test
    void transactionFailureIsServiceUnavailable() {
        var response = handler.handleDatabaseUnavailable(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"),
                request());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(50300);
        assertThat(response.getBody().message()).isEqualTo("数据库暂不可用，请稍后重试");
    }

    @Test
    void connectionFailureIsServiceUnavailableWithoutLeakingDetails() {
        var response = handler.handleDatabaseUnavailable(
                new CannotGetJdbcConnectionException(
                        "Failed to obtain JDBC Connection: jdbc:mysql://127.0.0.1:3307/root",
                        new java.sql.SQLException("access denied for user 'root'")), request());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        String message = response.getBody() == null ? "" : response.getBody().message();
        assertThat(message).doesNotContain("jdbc:", "root", "SQL", "Exception");
    }
}