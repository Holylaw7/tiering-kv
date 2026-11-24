package io.tieringkv.webconsole.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/** 备份异步执行线程池（TD-091）：小并发、有界队列，避免导出打满请求线程。 */
@Configuration
public class AsyncConfig {

    @Bean(name = "backupExecutor")
    public Executor backupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("backup-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** O3-B：告警 Webhook 异步派发线程池，有界队列 + 丢弃最旧策略，确保绝不阻塞指标采集。 */
    @Bean(name = "alertWebhookExecutor")
    public Executor alertWebhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("alert-webhook-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
        executor.initialize();
        return executor;
    }
}
