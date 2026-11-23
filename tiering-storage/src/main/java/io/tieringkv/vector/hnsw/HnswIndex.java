package io.tieringkv.vector.hnsw;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * HNSW 多层图检索（ADR-0332）：分层邻居图 + 贪心下降 + efSearch
 * 候选扩展，替代 ADR-0117 的"分层列表 + 全量扫描"简化原型。
 *
 * <p>构建（离线批量）：按 splitmix64(id.hashCode()) 生成确定性随机
 * 层级，逐层贪心连接（双向边），邻居超限按距离裁剪；搜索从入口节点
 * 逐层下降至层 0，在层 0 按 efSearch 扩展候选，返回余弦相似度 topK。
 *
 * <p>小索引（≤ {@link #EXACT_THRESHOLD}）退化为暴力扫描，避免近似
 * 误差且开销更低；全零向量不参与连接与检索（与 VectorStore 语义
 * 一致）。{@link #serialize}/{@link #deserialize} 为带版本的多层图
 * 格式（参数 + 向量 + 层邻居边 + 入口节点）。
 */
public final class HnswIndex {

    /** 默认图参数（ADR-0332 基准矩阵）。 */
    public static final int DEFAULT_M = 16;
    public static final int DEFAULT_MMAX = 32;
    public static final int DEFAULT_EF_CONSTRUCTION = 64;
    public static final int DEFAULT_EF_SEARCH = 48;
    public static final int EXACT_THRESHOLD = 256;

    private static final byte[] MAGIC = {'T', 'K', 'H', 'N'};
    private static final int FORMAT_VERSION = 1;
    private static final double MIN_UNIFORM = 1e-9;

    private final int maxLevel;
    private final int m;
    private final int mmax;
    private final int efConstruction;
    private final int efSearch;
    private final double mL;

    private final List<Node> nodes = new ArrayList<>();
    private final Map<String, Integer> idToIndex = new HashMap<>();
    private int entryPoint = -1;

    public HnswIndex(int maxLevel) {
        this(maxLevel, DEFAULT_M, DEFAULT_MMAX,
                DEFAULT_EF_CONSTRUCTION, DEFAULT_EF_SEARCH);
    }

    public HnswIndex(int maxLevel, int m, int mmax,
                     int efConstruction, int efSearch) {
        this.maxLevel = Math.max(1, maxLevel);
        this.m = Math.max(1, m);
        this.mmax = Math.max(this.m, mmax);
        this.efConstruction = Math.max(1, efConstruction);
        this.efSearch = Math.max(1, efSearch);
        this.mL = 1.0 / Math.log(this.m);
    }

    /** 批量构建：重复 id 保留首条（与唯一向量集语义一致）。 */
    public void build(List<Embedding> embeddings) {
        for (Embedding embedding : embeddings) {
            insert(embedding);
        }
    }

    public List<VectorStore.ScoredEmbedding> search(float[] query,
                                                    int topK) {
        if (nodes.isEmpty() || entryPoint < 0 || topK <= 0) {
            return List.of();
        }
        if (nodes.size() <= EXACT_THRESHOLD) {
            return bruteSearch(query, topK);
        }
        int current = entryPoint;
        for (int layer = nodes.get(entryPoint).level; layer > 0;
             layer--) {
            current = searchLayer(current, layer, query, 1)
                    .get(0).index;
        }
        List<Candidate> layer0 = searchLayer(current, 0, query,
                efSearch);
        Comparator<Candidate> byDistance = Comparator
                .comparingDouble(Candidate::distance)
                .thenComparing(candidate -> nodes.get(candidate.index)
                        .id);
        layer0.sort(byDistance);
        List<VectorStore.ScoredEmbedding> results = new ArrayList<>();
        for (Candidate candidate : layer0) {
            if (results.size() >= topK) {
                break;
            }
            results.add(new VectorStore.ScoredEmbedding(
                    nodes.get(candidate.index).id,
                    1.0 - candidate.distance));
        }
        return results;
    }

    public int size() {
        return nodes.size();
    }

    /** 有向边总数（图结构非空验证 / 序列化一致性断言）。 */
    public int edgeCount() {
        int edges = 0;
        for (Node node : nodes) {
            for (List<Integer> neighbors : node.neighbors) {
                edges += neighbors.size();
            }
        }
        return edges;
    }

    /** 序列化：magic + 版本 + 参数 + 入口 + 节点{id+向量+层邻居边}。 */
    public byte[] serialize() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeInt(FORMAT_VERSION);
        out.writeInt(maxLevel);
        out.writeInt(m);
        out.writeInt(mmax);
        out.writeInt(efConstruction);
        out.writeInt(efSearch);
        out.writeDouble(mL);
        out.writeInt(entryPoint);
        out.writeInt(nodes.size());
        for (Node node : nodes) {
            writeText(out, node.id);
            out.writeInt(node.values.length);
            for (float value : node.values) {
                out.writeFloat(value);
            }
            out.writeInt(node.level);
            for (int layer = 0; layer <= node.level; layer++) {
                List<Integer> neighbors = node.neighbors.get(layer);
                out.writeInt(neighbors.size());
                for (int neighbor : neighbors) {
                    out.writeInt(neighbor);
                }
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    /** 反序列化重建完整多层图（搜索结果与序列化前一致）。 */
    public static HnswIndex deserialize(byte[] bytes)
            throws IOException {
        if (bytes == null || bytes.length < 4 + 4 + 4) {
            throw new IOException("HNSW index bytes too short");
        }
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(bytes));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("invalid HNSW index magic");
        }
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException(
                    "unsupported HNSW index version " + version);
        }
        int maxLevel = in.readInt();
        int m = in.readInt();
        int mmax = in.readInt();
        int efConstruction = in.readInt();
        int efSearch = in.readInt();
        in.readDouble(); // mL 由 m 重算，读位保持格式一致
        HnswIndex index = new HnswIndex(maxLevel, m, mmax,
                efConstruction, efSearch);
        index.entryPoint = in.readInt();
        int count = in.readInt();
        if (index.entryPoint < -1 || index.entryPoint >= count
                || count < 0 || count > 100_000_000) {
            throw new IOException("invalid HNSW header "
                    + "(entry=" + index.entryPoint
                    + ", count=" + count + ")");
        }
        for (int i = 0; i < count; i++) {
            String id = readText(in);
            int dim = in.readInt();
            if (dim < 0 || dim > 1_000_000) {
                throw new IOException("invalid HNSW dim " + dim);
            }
            float[] values = new float[dim];
            for (int d = 0; d < dim; d++) {
                values[d] = in.readFloat();
            }
            int level = in.readInt();
            if (level < 0 || level >= index.maxLevel) {
                throw new IOException(
                        "invalid HNSW level " + level);
            }
            Node node = new Node(id, values, level);
            for (int layer = 0; layer <= level; layer++) {
                int neighborCount = in.readInt();
                if (neighborCount < 0 || neighborCount > index.mmax) {
                    throw new IOException(
                            "invalid HNSW neighbor count "
                                    + neighborCount);
                }
                for (int k = 0; k < neighborCount; k++) {
                    int neighbor = in.readInt();
                    if (neighbor < 0 || neighbor >= count) {
                        throw new IOException(
                                "invalid HNSW neighbor index "
                                        + neighbor);
                    }
                    node.neighbors.get(layer).add(neighbor);
                }
            }
            index.nodes.add(node);
            index.idToIndex.put(id, i);
        }
        return index;
    }

    private void insert(Embedding embedding) {
        if (idToIndex.containsKey(embedding.id())) {
            return; // 重复 id 保留首条
        }
        int index = nodes.size();
        boolean zero = isZero(embedding.values());
        int level = zero ? 0 : randomLevel(embedding.id());
        Node node = new Node(embedding.id(), embedding.values(), level);
        nodes.add(node);
        idToIndex.put(embedding.id(), index);
        if (zero || entryPoint < 0) {
            if (!zero && entryPoint < 0) {
                entryPoint = index;
            }
            return; // 全零向量隔离；首个非零向量建立入口
        }

        float[] query = embedding.values();
        int current = entryPoint;
        int entryLevel = nodes.get(entryPoint).level;
        for (int layer = entryLevel; layer > level; layer--) {
            current = searchLayer(current, layer, query, 1)
                    .get(0).index;
        }
        for (int layer = Math.min(level, entryLevel); layer >= 0;
             layer--) {
            List<Candidate> candidates = searchLayer(current, layer,
                    query, efConstruction);
            List<Integer> neighbors = selectNeighbors(candidates, m);
            node.neighbors.set(layer, neighbors);
            for (int neighbor : neighbors) {
                List<Integer> reverse =
                        nodes.get(neighbor).neighbors.get(layer);
                if (!reverse.contains(index)) {
                    reverse.add(index);
                }
                if (reverse.size() > mmax) {
                    shrinkNeighbors(neighbor, layer);
                }
            }
            current = candidates.isEmpty()
                    ? current : candidates.get(0).index;
        }
        if (level > nodes.get(entryPoint).level) {
            entryPoint = index;
        }
    }

    /**
     * 单层贪心搜索：从 entry 出发按最近邻扩展，候选集上限 ef；
     * 返回按距离升序的 ef 个最近节点。
     */
    private List<Candidate> searchLayer(int entry, int layer,
                                        float[] query, int ef) {
        PriorityQueue<Candidate> candidates = new PriorityQueue<>(
                Comparator.comparingDouble(Candidate::distance));
        PriorityQueue<Candidate> results = new PriorityQueue<>(
                Comparator.comparingDouble(Candidate::distance)
                        .reversed());
        Set<Integer> visited = new HashSet<>();
        double entryDist = distance(query, nodes.get(entry).values);
        if (Double.isInfinite(entryDist)) {
            return List.of(new Candidate(entry, entryDist));
        }
        candidates.add(new Candidate(entry, entryDist));
        results.add(new Candidate(entry, entryDist));
        visited.add(entry);
        while (!candidates.isEmpty()) {
            Candidate closest = candidates.poll();
            double worst = results.isEmpty() ? Double.MAX_VALUE
                    : results.peek().distance;
            if (closest.distance > worst && results.size() >= ef) {
                break;
            }
            for (int neighbor : nodes.get(closest.index)
                    .neighbors.get(layer)) {
                if (!visited.add(neighbor)) {
                    continue;
                }
                double dist = distance(query,
                        nodes.get(neighbor).values);
                if (Double.isInfinite(dist)) {
                    continue;
                }
                worst = results.peek().distance;
                if (results.size() < ef || dist < worst) {
                    candidates.add(new Candidate(neighbor, dist));
                    results.add(new Candidate(neighbor, dist));
                    if (results.size() > ef) {
                        results.poll(); // 移除最远
                    }
                }
            }
        }
        List<Candidate> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparingDouble(Candidate::distance));
        return sorted;
    }

    /** 取候选集最近的前 limit 个节点。 */
    private static List<Integer> selectNeighbors(
            List<Candidate> candidates, int limit) {
        List<Integer> neighbors = new ArrayList<>();
        int count = 0;
        for (Candidate candidate : candidates) {
            if (count >= limit
                    || Double.isInfinite(candidate.distance)) {
                break;
            }
            neighbors.add(candidate.index);
            count++;
        }
        return neighbors;
    }

    /** 邻居超限：按到中心节点的距离排序并裁剪至 mmax。 */
    private void shrinkNeighbors(int nodeIndex, int layer) {
        List<Integer> neighbors =
                nodes.get(nodeIndex).neighbors.get(layer);
        float[] center = nodes.get(nodeIndex).values;
        neighbors.sort(Comparator.comparingDouble(
                neighbor -> distance(center,
                        nodes.get(neighbor).values)));
        while (neighbors.size() > mmax) {
            neighbors.remove(neighbors.size() - 1);
        }
    }

    /** 小索引暴力检索（与 VectorStore 语义一致，id 字典序稳定）。 */
    private List<VectorStore.ScoredEmbedding> bruteSearch(
            float[] query, int topK) {
        List<VectorStore.ScoredEmbedding> results = new ArrayList<>();
        for (Node node : nodes) {
            if (isZero(node.values)) {
                continue;
            }
            results.add(new VectorStore.ScoredEmbedding(node.id,
                    VectorStore.cosine(query, node.values)));
        }
        results.sort(Comparator
                .comparingDouble(VectorStore.ScoredEmbedding::score)
                .reversed()
                .thenComparing(VectorStore.ScoredEmbedding::id));
        return results.size() > topK
                ? List.copyOf(results.subList(0, topK)) : results;
    }

    /** 确定性层级：splitmix64(id.hashCode()) 映射到 [0, maxLevel)。 */
    private int randomLevel(String id) {
        long hash = mix64(id.hashCode());
        double uniform = (hash >>> 11) * 0x1.0p-53;
        if (uniform <= 0) {
            uniform = MIN_UNIFORM;
        }
        int level = (int) (-Math.log(uniform) * mL);
        return Math.min(level, maxLevel - 1);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static double distance(float[] a, float[] b) {
        if (isZero(a) || isZero(b) || a.length != b.length
                || a.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return 1.0 - VectorStore.cosine(a, b);
    }

    private static boolean isZero(float[] values) {
        for (float value : values) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static void writeText(DataOutputStream out, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in)
            throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private record Candidate(int index, double distance) {
    }

    private static final class Node {
        private final String id;
        private final float[] values;
        private final int level;
        private final List<List<Integer>> neighbors;

        private Node(String id, float[] values, int level) {
            this.id = id;
            this.values = values.clone();
            this.level = level;
            this.neighbors = new ArrayList<>(level + 1);
            for (int i = 0; i <= level; i++) {
                neighbors.add(new ArrayList<>());
            }
        }
    }
}
