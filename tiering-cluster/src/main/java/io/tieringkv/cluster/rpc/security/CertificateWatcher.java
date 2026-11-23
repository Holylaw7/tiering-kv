package io.tieringkv.cluster.rpc.security;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 证书文件监听（ADR-0055）：文件变更触发 reload 回调。 */
public final class CertificateWatcher implements AutoCloseable {

    private final WatchService watchService;
    private final ExecutorService executor;
    private final Path dir;
    private volatile Runnable onChange;
    private volatile boolean running = true;

    public CertificateWatcher(Path dir) throws IOException {
        this.dir = dir;
        this.watchService = FileSystems.getDefault().newWatchService();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cert-watcher");
            thread.setDaemon(true);
            return thread;
        });
        dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE);
        executor.submit(this::watchLoop);
    }

    public void onChange(Runnable onChange) {
        this.onChange = onChange;
    }

    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = dir.resolve((Path) event.context());
                    if (changed.toString().endsWith(".crt")
                            || changed.toString().endsWith(".key")) {
                        Runnable callback = onChange;
                        if (callback != null) {
                            callback.run();
                        }
                    }
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        executor.shutdownNow();
        try {
            watchService.close();
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
