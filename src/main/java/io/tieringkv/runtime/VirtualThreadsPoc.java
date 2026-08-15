package io.tieringkv.runtime;

import java.util.concurrent.ExecutorService;

/**
 * JDK 21 虚拟线程 POC 入口（ADR-0331，TD-002）：JDK 21 运行时输出
 * 执行器类型并完成简单任务；JDK 17 下演示回退。
 * 用法：java -cp target/classes io.tieringkv.runtime.VirtualThreadsPoc
 */
public final class VirtualThreadsPoc {

    private VirtualThreadsPoc() {
    }

    public static void main(String[] args) throws Exception {
        ExecutorService executor =
                GatewayRuntime.createWorkerExecutor(true);
        System.out.println("PHASE64-POC executor="
                + executor.getClass().getSimpleName()
                + " java=" + System.getProperty("java.version"));
        int tasks = args.length > 0 ? Integer.parseInt(args[0]) : 10_000;
        long start = System.nanoTime();
        java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) {
            final int id = i;
            executor.submit(() -> {
                Math.sqrt(id);
                done.countDown();
            });
        }
        done.await();
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        System.out.printf(
                "PHASE64-POC tasks=%d ops/s=%.0f%n",
                tasks, tasks / seconds);
        executor.shutdownNow();
    }
}
