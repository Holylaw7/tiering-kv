package io.tieringkv.vector.collection;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.indexfile.VectorIndexFile;
import io.tieringkv.vector.indexfile.VectorIndexStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 向量多集合命名空间（ADR-0338）：collection → VectorIndexStore，
 * dirty 跟踪 + 原子 checkpoint + 自动刷盘。
 *
 * <p>写入/删除标记集合 dirty；{@link #checkpoint}/{@link #checkpointAll}
 * 以 VectorIndexFile 既有格式原子落盘（`<collection>.tvif`）；
 * {@link #startAutoCheckpoint} 后台 daemon 定时刷脏，{@link #close}
 * 停调度并兜底全量 checkpoint（与水位周期模式一致）。
 */
public final class VectorCollectionRegistry implements AutoCloseable {

    public static final String DEFAULT_COLLECTION = "default";

    private final int defaultMaxLevel;
    private final Map<String, VectorIndexStore> collections =
            new ConcurrentHashMap<>();
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private volatile Path checkpointDir;
    private volatile ScheduledExecutorService scheduler;

    public VectorCollectionRegistry() {
        this(6);
    }

    public VectorCollectionRegistry(int defaultMaxLevel) {
        this.defaultMaxLevel = Math.max(1, defaultMaxLevel);
    }

    /** 兼容既有单 store 接线：默认集合指向注入实例。 */
    public static VectorCollectionRegistry ofDefault(
            VectorIndexStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store required");
        }
        VectorCollectionRegistry registry = new VectorCollectionRegistry(
                store.maxLevel());
        registry.collections.put(DEFAULT_COLLECTION, store);
        return registry;
    }

    public VectorIndexStore collection(String name) {
        return collections.computeIfAbsent(normalize(name),
                ignored -> new VectorIndexStore(defaultMaxLevel));
    }

    /** 非创建读取：缺失返回 null（搜索空集合/错误判定）。 */
    public VectorIndexStore collectionIfPresent(String name) {
        return collections.get(normalize(name));
    }

    public boolean hasCollection(String name) {
        return collections.containsKey(normalize(name));
    }

    public boolean drop(String name) {
        boolean removed = collections.remove(normalize(name)) != null;
        dirty.remove(normalize(name));
        return removed;
    }

    /** 集合名（字典序，含 default）。 */
    public List<String> names() {
        return new ArrayList<>(new TreeSet<>(collections.keySet()));
    }

    public int size() {
        return collections.size();
    }

    public int size(String name) {
        VectorIndexStore store = collectionIfPresent(name);
        return store == null ? 0 : store.size();
    }

    public Set<String> dirtyNames() {
        return Set.copyOf(dirty);
    }

    public void put(String collection, Embedding embedding) {
        String name = normalize(collection);
        collection(name).put(embedding);
        dirty.add(name);
    }

    public boolean delete(String collection, String id) {
        String name = normalize(collection);
        VectorIndexStore store = collections.get(name);
        if (store == null) {
            return false;
        }
        boolean removed = store.delete(id);
        dirty.add(name);
        return removed;
    }

    public void configureCheckpoint(Path directory) {
        this.checkpointDir = directory;
    }

    public Path checkpointDir() {
        return checkpointDir;
    }

    public synchronized void checkpoint(String collection, Path directory)
            throws IOException {
        String name = normalize(collection);
        VectorIndexStore store = collections.get(name);
        if (store == null) {
            throw new IOException("no collection " + name);
        }
        Path target = directory != null ? directory : checkpointDir;
        if (target == null) {
            throw new IOException("checkpoint dir not configured");
        }
        Files.createDirectories(target);
        VectorIndexFile.write(target.resolve(name + ".tvif"),
                new VectorIndexFile.IndexData(store.maxLevel(),
                        store.dim(), store.snapshot()));
        dirty.remove(name);
    }

    public synchronized void checkpointAll(Path directory)
            throws IOException {
        Path target = directory != null ? directory : checkpointDir;
        if (target == null) {
            throw new IOException("checkpoint dir not configured");
        }
        for (String name : collections.keySet()) {
            checkpoint(name, target);
        }
    }

    public static VectorCollectionRegistry loadAll(Path directory)
            throws IOException {
        VectorCollectionRegistry registry = new VectorCollectionRegistry();
        if (!Files.isDirectory(directory)) {
            return registry;
        }
        try (var paths = Files.list(directory)) {
            List<Path> files = paths
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".tvif"))
                    .toList();
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String name = fileName.substring(0,
                        fileName.length() - ".tvif".length());
                registry.collections.put(name,
                        VectorIndexStore.load(file));
            }
        }
        return registry;
    }

    /** 定时刷脏集合（intervalMillis > 0）；重复调用幂等。 */
    public synchronized void startAutoCheckpoint(long intervalMillis) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException(
                    "intervalMillis must be positive");
        }
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "vector-collection-checkpoint");
                    thread.setDaemon(true);
                    return thread;
                });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                for (String name : dirtyNames()) {
                    checkpoint(name, null);
                }
            } catch (IOException ignored) {
                // 下次周期重试；close 兜底
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public boolean autoCheckpointRunning() {
        return scheduler != null;
    }

    @Override
    public synchronized void close() throws IOException {
        ScheduledExecutorService executor = scheduler;
        if (executor != null) {
            executor.shutdownNow();
            scheduler = null;
        }
        if (checkpointDir != null) {
            checkpointAll(checkpointDir);
        }
    }

    private static String normalize(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "collection name required");
        }
        return name;
    }
}
