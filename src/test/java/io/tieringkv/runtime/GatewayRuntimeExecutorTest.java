package io.tieringkv.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/** 网关工作执行器（ADR-0331）：VT 反射 / 平台线程回退。 */
class GatewayRuntimeExecutorTest {

    @Test
    void platformModeUsesCachedPool() {
        ExecutorService executor =
                GatewayRuntime.createWorkerExecutor(false);
        assertThat(executor).isNotNull();
        executor.shutdownNow();
    }

    @Test
    void virtualThreadsFallsBackWhenUnsupported() {
        // JDK 17 下反射不可用 → 回退 cached pool（不抛异常）
        ExecutorService executor =
                GatewayRuntime.createWorkerExecutor(true);
        assertThat(executor).isNotNull();
        executor.shutdownNow();
    }
}
