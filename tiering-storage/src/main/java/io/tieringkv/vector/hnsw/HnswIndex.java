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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HNSW 多层图检索（ADR-0332 / B4 高性能重构）：扁平原始邻接数组、预归一化单位向量、
 * 零装箱 {@link ThreadLocal} 搜索上下文、SQ8 标量量化与可插拔 FMA/SIMD 距离核。
 *
 * <p>核心优化：
 * <ul>
 *   <li>邻接表采用扁平原始 {@code int[][]} 与计数 {@code int[]}，消除 {@code List<Integer>} 装箱开销；</li>
 *   <li>构建期预计算单位归一化向量 {@code unitValues}，余弦距离退化为 {@code 1 - dot(qUnit, nUnit)}；</li>
 *   <li>上层（layer &gt; 0）执行零分配贪心下降，层 0 使用代数标记 {@code visitedGen[]} 与原始类型小/大根堆；</li>
 *   <li>支持 {@link QuantizationMode#NONE}（Float32，TKHN v1 兼容）、{@link QuantizationMode#SQ8}（纯 int8，
 *       向量内存下降 ~75%）与 {@link QuantizationMode#SQ8_RERANK}（SQ8 导航 + Float32 两阶段精确重排）；</li>
 *   <li>{@link #deserialize(byte[])} 同时兼容 {@code TKHN} v1 与 v2 格式。</li>
 * </ul>
 */
public final class HnswIndex {

    /** 默认图参数（ADR-0332 基准矩阵）。 */
    public static final int DEFAULT_M = 16;
    public static final int DEFAULT_MMAX = 32;
    public static final int DEFAULT_EF_CONSTRUCTION = 64;
    public static final int DEFAULT_EF_SEARCH = 48;
    public static final int EXACT_THRESHOLD = 256;

    private static final byte[] MAGIC = {'T', 'K', 'H', 'N'};
    private static final int FORMAT_VERSION_V1 = 1;
    private static final int FORMAT_VERSION_V2 = 2;
    private static final double MIN_UNIFORM = 1e-9;
    private static final int[] EMPTY_INTS = new int[0];
    private static final int MAX_DESERIALIZE_NODES = 100_000_000;
    private static final int MAX_DESERIALIZE_DIM = 1_000_000;

    private static final ThreadLocal<SearchWorkspace> WORKSPACE =
            ThreadLocal.withInitial(SearchWorkspace::new);

    /** 向量量化模式（B4）。 */
    public enum QuantizationMode {
        /** 32-bit 预归一化浮点向量（兼容 TKHN v1 序列化格式）。 */
        NONE((byte) 0),
        /** 8-bit 每向量对称 abs-max 标量量化（仅保留 int8 向量，向量内存下降 ~75%）。 */
        SQ8((byte) 1),
        /** SQ8 图导航 + Top-K 候选 Float32 两阶段精确重排。 */
        SQ8_RERANK((byte) 2);

        private final byte code;

        QuantizationMode(byte code) {
            this.code = code;
        }

        byte code() {
            return code;
        }

        static QuantizationMode fromCode(byte code) throws IOException {
            for (QuantizationMode mode : values()) {
                if (mode.code == code) {
                    return mode;
                }
            }
            throw new IOException("unsupported HNSW quantization mode " + code);
        }
    }

    private final int maxLevel;
    private final int m;
    private final int mmax;
    private final int efConstruction;
    private final int efSearch;
    private final double mL;
    private final QuantizationMode quantizationMode;
    private final VectorDistanceKernel kernel;

    private final List<Node> nodes = new ArrayList<>();
    private final Map<String, Integer> idToIndex = new HashMap<>();
    private int entryPoint = -1;
    private boolean rawVectorsReleased;

    public HnswIndex(int maxLevel) {
        this(maxLevel, DEFAULT_M, DEFAULT_MMAX,
                DEFAULT_EF_CONSTRUCTION, DEFAULT_EF_SEARCH,
                QuantizationMode.NONE, VectorDistanceKernel.active());
    }

    public HnswIndex(int maxLevel, QuantizationMode quantizationMode) {
        this(maxLevel, DEFAULT_M, DEFAULT_MMAX,
                DEFAULT_EF_CONSTRUCTION, DEFAULT_EF_SEARCH,
                quantizationMode, VectorDistanceKernel.active());
    }

    public HnswIndex(int maxLevel, int m, int mmax,
                     int efConstruction, int efSearch) {
        this(maxLevel, m, mmax, efConstruction, efSearch,
                QuantizationMode.NONE, VectorDistanceKernel.active());
    }

    public HnswIndex(int maxLevel, int m, int mmax,
                     int efConstruction, int efSearch,
                     QuantizationMode quantizationMode) {
        this(maxLevel, m, mmax, efConstruction, efSearch,
                quantizationMode, VectorDistanceKernel.active());
    }

    public HnswIndex(int maxLevel, int m, int mmax,
                     int efConstruction, int efSearch,
                     QuantizationMode quantizationMode,
                     VectorDistanceKernel kernel) {
        this.maxLevel = Math.max(1, maxLevel);
        this.m = Math.max(1, m);
        this.mmax = Math.max(this.m, mmax);
        this.efConstruction = Math.max(1, efConstruction);
        this.efSearch = Math.max(1, efSearch);
        this.mL = 1.0 / Math.log(this.m);
        this.quantizationMode = Objects.requireNonNull(quantizationMode, "quantizationMode");
        this.kernel = Objects.requireNonNull(kernel, "kernel");
    }

    public QuantizationMode quantizationMode() {
        return quantizationMode;
    }

    public VectorDistanceKernel distanceKernel() {
        return kernel;
    }

    /** 估算所有节点向量载荷占用字节数（仅统计向量数组与量化缩放因子）。 */
    public long estimatedVectorBytes() {
        long bytes = 0L;
        for (Node node : nodes) {
            if (node.unitValues != null) {
                bytes += 16L + ((long) node.unitValues.length << 2);
            }
            if (node.rawValues != null && node.rawValues != node.unitValues) {
                bytes += 16L + ((long) node.rawValues.length << 2);
            }
            if (node.sq8Values != null) {
                bytes += 16L + node.sq8Values.length + 4L;
            }
        }
        return bytes;
    }

    /** 批量构建：重复 id 保留首条（与唯一向量集语义一致）。 */
    public void build(List<Embedding> embeddings) {
        for (Embedding embedding : embeddings) {
            insert(embedding);
        }
    }

    public List<VectorStore.ScoredEmbedding> search(float[] query, int topK) {
        if (nodes.isEmpty() || entryPoint < 0 || topK <= 0) {
            return List.of();
        }
        if (nodes.size() <= EXACT_THRESHOLD) {
            return bruteSearch(query, topK);
        }
        Node entryNode = nodes.get(entryPoint);
        if (query == null || query.length != entryNode.dim || query.length == 0) {
            return List.of();
        }
        SearchWorkspace ws = WORKSPACE.get();
        float[] queryUnit = ws.normalizeQuery(query);
        if (queryUnit == null) {
            return List.of();
        }

        int current = entryPoint;
        for (int layer = entryNode.level; layer > 0; layer--) {
            current = greedySearchLayer(current, layer, queryUnit);
        }

        int searchEf = quantizationMode == QuantizationMode.SQ8_RERANK
                ? Math.max(efSearch, topK * 3)
                : Math.max(efSearch, topK);
        int candidateCount = searchLayer(current, 0, queryUnit, searchEf, ws);
        if (candidateCount == 0) {
            return List.of();
        }

        int[] outIdx = ws.outIndices;
        float[] outDist = ws.outDistances;
        if (quantizationMode == QuantizationMode.SQ8_RERANK) {
            for (int i = 0; i < candidateCount; i++) {
                Node node = nodes.get(outIdx[i]);
                if (node.unitValues != null) {
                    outDist[i] = exactDistance(queryUnit, node.unitValues);
                }
            }
            sortCandidatesWithIdTieBreak(outIdx, outDist, candidateCount);
        } else {
            tieBreakEqualDistancesById(outIdx, outDist, candidateCount);
        }

        int limit = Math.min(topK, candidateCount);
        List<VectorStore.ScoredEmbedding> results = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Node node = nodes.get(outIdx[i]);
            results.add(new VectorStore.ScoredEmbedding(node.id, 1.0 - outDist[i]));
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
            for (int count : node.neighborCounts) {
                edges += count;
            }
        }
        return edges;
    }

    /**
     * 序列化：
     * <ul>
     *   <li>{@link QuantizationMode#NONE} 写 {@code TKHN} v1（与历史版本 100% 字节兼容）；</li>
     *   <li>{@link QuantizationMode#SQ8} / {@link QuantizationMode#SQ8_RERANK} 写 {@code TKHN} v2。</li>
     * </ul>
     */
    public byte[] serialize() throws IOException {
        if (quantizationMode == QuantizationMode.NONE) {
            return serializeV1();
        }
        return serializeV2();
    }

    private byte[] serializeV1() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeInt(FORMAT_VERSION_V1);
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
            float[] payload = node.rawValues != null ? node.rawValues : node.unitValues;
            int dim = payload != null ? payload.length : 0;
            out.writeInt(dim);
            if (payload != null) {
                for (int d = 0; d < dim; d++) {
                    out.writeFloat(payload[d]);
                }
            }
            out.writeInt(node.level);
            for (int layer = 0; layer <= node.level; layer++) {
                int count = node.neighborCounts[layer];
                int[] nbrs = node.neighbors[layer];
                out.writeInt(count);
                for (int k = 0; k < count; k++) {
                    out.writeInt(nbrs[k]);
                }
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    private byte[] serializeV2() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        boolean hasFloatPayload = quantizationMode == QuantizationMode.SQ8_RERANK
                || nodes.size() <= EXACT_THRESHOLD;
        out.write(MAGIC);
        out.writeInt(FORMAT_VERSION_V2);
        out.writeByte(quantizationMode.code());
        out.writeBoolean(hasFloatPayload);
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
            out.writeInt(node.dim);
            out.writeBoolean(node.zero);
            out.writeFloat(node.sq8Scale);
            if (node.sq8Values != null && node.sq8Values.length > 0) {
                out.write(node.sq8Values);
            }
            if (hasFloatPayload) {
                float[] payload = node.rawValues != null ? node.rawValues : node.unitValues;
                for (int d = 0; d < node.dim; d++) {
                    out.writeFloat(payload != null ? payload[d] : 0.0f);
                }
            }
            out.writeInt(node.level);
            for (int layer = 0; layer <= node.level; layer++) {
                int count = node.neighborCounts[layer];
                int[] nbrs = node.neighbors[layer];
                out.writeInt(count);
                for (int k = 0; k < count; k++) {
                    out.writeInt(nbrs[k]);
                }
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    /** 反序列化重建完整多层图（兼容 {@code TKHN} v1 与 v2）。 */
    public static HnswIndex deserialize(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 4 + 4 + 4) {
            throw new IOException("HNSW index bytes too short");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("invalid HNSW index magic");
        }
        int version = in.readInt();
        if (version == FORMAT_VERSION_V1) {
            return deserializeV1(in);
        }
        if (version == FORMAT_VERSION_V2) {
            return deserializeV2(in);
        }
        throw new IOException("unsupported HNSW index version " + version);
    }

    private static HnswIndex deserializeV1(DataInputStream in) throws IOException {
        int maxLevel = in.readInt();
        int m = in.readInt();
        int mmax = in.readInt();
        int efConstruction = in.readInt();
        int efSearch = in.readInt();
        in.readDouble(); // mL 由 m 重算，读位保持格式一致
        HnswIndex index = new HnswIndex(maxLevel, m, mmax, efConstruction, efSearch,
                QuantizationMode.NONE, VectorDistanceKernel.active());
        index.entryPoint = in.readInt();
        int count = in.readInt();
        validateHeader(index.entryPoint, count);
        boolean keepRaw = count <= EXACT_THRESHOLD;
        index.rawVectorsReleased = !keepRaw;
        for (int i = 0; i < count; i++) {
            String id = readText(in);
            int dim = readDim(in);
            float[] values = new float[dim];
            for (int d = 0; d < dim; d++) {
                values[d] = in.readFloat();
            }
            int level = readLevel(in, index.maxLevel);
            boolean zero = isZero(values);
            float[] unit = zero ? new float[dim] : normalizeIfNeeded(values);
            Node node = new Node(id, dim, level, zero,
                    keepRaw ? values : null, unit, null, 0.0f);
            readNeighbors(in, node, index.mmax, count);
            index.nodes.add(node);
            index.idToIndex.put(id, i);
        }
        return index;
    }

    private static HnswIndex deserializeV2(DataInputStream in) throws IOException {
        QuantizationMode mode = QuantizationMode.fromCode(in.readByte());
        boolean hasFloatPayload = in.readBoolean();
        int maxLevel = in.readInt();
        int m = in.readInt();
        int mmax = in.readInt();
        int efConstruction = in.readInt();
        int efSearch = in.readInt();
        in.readDouble();
        HnswIndex index = new HnswIndex(maxLevel, m, mmax, efConstruction, efSearch,
                mode, VectorDistanceKernel.active());
        index.entryPoint = in.readInt();
        int count = in.readInt();
        validateHeader(index.entryPoint, count);
        boolean keepRaw = count <= EXACT_THRESHOLD;
        index.rawVectorsReleased = !keepRaw;
        for (int i = 0; i < count; i++) {
            String id = readText(in);
            int dim = readDim(in);
            boolean zero = in.readBoolean();
            float sq8Scale = in.readFloat();
            byte[] sq8Values = new byte[dim];
            if (dim > 0) {
                in.readFully(sq8Values);
            }
            float[] rawValues = null;
            float[] unitValues = null;
            if (hasFloatPayload) {
                float[] loaded = new float[dim];
                for (int d = 0; d < dim; d++) {
                    loaded[d] = in.readFloat();
                }
                if (keepRaw) {
                    rawValues = loaded;
                }
                if (mode == QuantizationMode.SQ8_RERANK || mode == QuantizationMode.NONE) {
                    unitValues = zero ? new float[dim] : normalizeIfNeeded(loaded);
                }
            }
            int level = readLevel(in, index.maxLevel);
            Node node = new Node(id, dim, level, zero, rawValues, unitValues, sq8Values, sq8Scale);
            readNeighbors(in, node, index.mmax, count);
            index.nodes.add(node);
            index.idToIndex.put(id, i);
        }
        return index;
    }

    private static void validateHeader(int entryPoint, int count) throws IOException {
        if (entryPoint < -1 || entryPoint >= count
                || count < 0 || count > MAX_DESERIALIZE_NODES) {
            throw new IOException("invalid HNSW header "
                    + "(entry=" + entryPoint + ", count=" + count + ")");
        }
    }

    private static int readDim(DataInputStream in) throws IOException {
        int dim = in.readInt();
        if (dim < 0 || dim > MAX_DESERIALIZE_DIM) {
            throw new IOException("invalid HNSW dim " + dim);
        }
        return dim;
    }

    private static int readLevel(DataInputStream in, int maxLevel) throws IOException {
        int level = in.readInt();
        if (level < 0 || level >= maxLevel) {
            throw new IOException("invalid HNSW level " + level);
        }
        return level;
    }

    private static void readNeighbors(DataInputStream in, Node node, int mmax, int totalNodes)
            throws IOException {
        for (int layer = 0; layer <= node.level; layer++) {
            int neighborCount = in.readInt();
            if (neighborCount < 0 || neighborCount > mmax) {
                throw new IOException("invalid HNSW neighbor count " + neighborCount);
            }
            int[] nbrs = neighborCount == 0 ? EMPTY_INTS : new int[neighborCount];
            for (int k = 0; k < neighborCount; k++) {
                int neighbor = in.readInt();
                if (neighbor < 0 || neighbor >= totalNodes) {
                    throw new IOException("invalid HNSW neighbor index " + neighbor);
                }
                nbrs[k] = neighbor;
            }
            node.neighbors[layer] = nbrs;
            node.neighborCounts[layer] = neighborCount;
        }
    }

    private void insert(Embedding embedding) {
        if (idToIndex.containsKey(embedding.id())) {
            return; // 重复 id 保留首条
        }
        float[] input = embedding.values();
        int dim = input.length;
        int index = nodes.size();
        boolean zero = isZero(input);
        int level = zero ? 0 : randomLevel(embedding.id());
        float[] unit = zero ? new float[dim] : normalizeCopy(input);

        boolean belowExactThreshold = index < EXACT_THRESHOLD;
        float[] storedRaw = belowExactThreshold ? input.clone() : null;
        float[] storedUnit;
        byte[] storedSq8;
        float storedSq8Scale;
        if (quantizationMode == QuantizationMode.NONE) {
            storedUnit = unit;
            storedSq8 = null;
            storedSq8Scale = 0.0f;
        } else {
            ScalarQuantizerSq8.QuantizedVector qv = ScalarQuantizerSq8.quantizeUnit(unit);
            storedSq8 = qv.rawValues();
            storedSq8Scale = qv.scale();
            storedUnit = quantizationMode == QuantizationMode.SQ8_RERANK ? unit : null;
        }

        Node node = new Node(embedding.id(), dim, level, zero,
                storedRaw, storedUnit, storedSq8, storedSq8Scale);
        nodes.add(node);
        idToIndex.put(embedding.id(), index);

        if (!rawVectorsReleased && nodes.size() > EXACT_THRESHOLD) {
            rawVectorsReleased = true;
            for (Node existing : nodes) {
                existing.rawValues = null;
            }
        }

        if (zero || entryPoint < 0) {
            if (!zero && entryPoint < 0) {
                entryPoint = index;
            }
            return; // 全零向量隔离；首个非零向量建立入口
        }

        SearchWorkspace ws = WORKSPACE.get();
        int current = entryPoint;
        int entryLevel = nodes.get(entryPoint).level;
        for (int layer = entryLevel; layer > level; layer--) {
            current = greedySearchLayer(current, layer, unit);
        }
        for (int layer = Math.min(level, entryLevel); layer >= 0; layer--) {
            int candidateCount = searchLayer(current, layer, unit, efConstruction, ws);
            int selected = selectNeighbors(node, layer, ws.outIndices, ws.outDistances,
                    candidateCount, m);
            int[] selectedNbrs = node.neighbors[layer];
            for (int i = 0; i < selected; i++) {
                int neighborIdx = selectedNbrs[i];
                Node neighborNode = nodes.get(neighborIdx);
                neighborNode.addNeighborUnique(layer, index, mmax + 1);
                if (neighborNode.neighborCounts[layer] > mmax) {
                    shrinkNeighbors(neighborNode, layer, ws);
                }
            }
            if (candidateCount > 0) {
                current = ws.outIndices[0];
            }
        }
        if (level > nodes.get(entryPoint).level) {
            entryPoint = index;
        }
    }

    /** 上层（layer &gt; 0）零分配 1-NN 贪心下降（HNSW Algorithm 2）。 */
    private int greedySearchLayer(int entry, int layer, float[] queryUnit) {
        int current = entry;
        float currentDist = distanceQueryToNode(queryUnit, nodes.get(current));
        if (Float.isInfinite(currentDist)) {
            return current;
        }
        boolean improved = true;
        while (improved) {
            improved = false;
            Node node = nodes.get(current);
            int[] nbrs = node.neighbors[layer];
            int count = node.neighborCounts[layer];
            for (int i = 0; i < count; i++) {
                int nbr = nbrs[i];
                float dist = distanceQueryToNode(queryUnit, nodes.get(nbr));
                if (dist < currentDist) {
                    currentDist = dist;
                    current = nbr;
                    improved = true;
                }
            }
        }
        return current;
    }

    /**
     * 单层候选扩展（零装箱）：使用代数标记数组与原始类型小/大根堆，
     * 将按距离升序排列的至多 {@code ef} 个结果写入 {@code ws.outIndices / ws.outDistances}。
     */
    private int searchLayer(int entry, int layer, float[] queryUnit, int ef, SearchWorkspace ws) {
        float entryDist = distanceQueryToNode(queryUnit, nodes.get(entry));
        if (Float.isInfinite(entryDist)) {
            ws.ensureOutputCapacity(1);
            ws.outIndices[0] = entry;
            ws.outDistances[0] = entryDist;
            return 1;
        }

        ws.resetVisited(nodes.size());
        IntFloatMinHeap candidates = ws.candidates;
        IntFloatMaxHeap results = ws.results;
        candidates.clear();
        results.clear();

        ws.markVisited(entry);
        candidates.push(entry, entryDist);
        results.push(entry, entryDist);

        while (!candidates.isEmpty()) {
            int closestIdx = candidates.peekIndex();
            float closestDist = candidates.peekDist();
            candidates.pop();

            float worst = results.peekDist();
            if (closestDist > worst && results.size() >= ef) {
                break;
            }

            Node closestNode = nodes.get(closestIdx);
            int[] nbrs = closestNode.neighbors[layer];
            int count = closestNode.neighborCounts[layer];
            for (int i = 0; i < count; i++) {
                int nbr = nbrs[i];
                if (!ws.markVisited(nbr)) {
                    continue;
                }
                float dist = distanceQueryToNode(queryUnit, nodes.get(nbr));
                if (Float.isInfinite(dist)) {
                    continue;
                }
                if (results.size() < ef) {
                    candidates.push(nbr, dist);
                    results.push(nbr, dist);
                } else if (dist < results.peekDist()) {
                    candidates.push(nbr, dist);
                    results.replaceTop(nbr, dist);
                }
            }
        }

        int resultCount = results.size();
        ws.ensureOutputCapacity(resultCount);
        for (int i = resultCount - 1; i >= 0; i--) {
            ws.outIndices[i] = results.peekIndex();
            ws.outDistances[i] = results.peekDist();
            results.pop();
        }
        return resultCount;
    }

    /** 取候选集最近的前 {@code limit} 个非无穷节点写入 {@code node.neighbors[layer]}。 */
    private static int selectNeighbors(Node node, int layer,
                                       int[] candIndices, float[] candDists,
                                       int candCount, int limit) {
        int maxSelect = Math.min(candCount, limit);
        int valid = 0;
        while (valid < maxSelect && !Float.isInfinite(candDists[valid])) {
            valid++;
        }
        if (valid == 0) {
            node.neighbors[layer] = EMPTY_INTS;
            node.neighborCounts[layer] = 0;
            return 0;
        }
        int[] selected = new int[valid];
        System.arraycopy(candIndices, 0, selected, 0, valid);
        node.neighbors[layer] = selected;
        node.neighborCounts[layer] = valid;
        return valid;
    }

    /** 邻居超限：一次性计算所有邻居距离，原地插入排序后裁剪至 {@code mmax}。 */
    private void shrinkNeighbors(Node center, int layer, SearchWorkspace ws) {
        int count = center.neighborCounts[layer];
        int[] nbrs = center.neighbors[layer];
        float[] dists = ws.ensureShrinkBuffer(count);
        for (int i = 0; i < count; i++) {
            dists[i] = distanceNodeToNode(center, nodes.get(nbrs[i]));
        }
        for (int i = 1; i < count; i++) {
            int idx = nbrs[i];
            float d = dists[i];
            int j = i - 1;
            while (j >= 0 && dists[j] > d) {
                nbrs[j + 1] = nbrs[j];
                dists[j + 1] = dists[j];
                j--;
            }
            nbrs[j + 1] = idx;
            dists[j + 1] = d;
        }
        center.neighborCounts[layer] = mmax;
    }

    /** 小索引暴力检索（与 VectorStore 语义完全一致，id 字典序稳定）。 */
    private List<VectorStore.ScoredEmbedding> bruteSearch(float[] query, int topK) {
        List<VectorStore.ScoredEmbedding> results = new ArrayList<>();
        for (Node node : nodes) {
            if (node.zero) {
                continue;
            }
            float[] vec = node.rawValues != null ? node.rawValues : node.unitValues;
            double score;
            if (vec != null) {
                score = VectorStore.cosine(query, vec);
            } else if (query != null && query.length == node.dim && node.sq8Values != null) {
                float[] qUnit = isZero(query) ? null : normalizeCopy(query);
                score = qUnit == null ? 0.0
                        : node.sq8Scale * kernel.dotProductFloatSq8(qUnit, node.sq8Values);
            } else {
                score = 0.0;
            }
            results.add(new VectorStore.ScoredEmbedding(node.id, score));
        }
        results.sort(Comparator
                .comparingDouble(VectorStore.ScoredEmbedding::score)
                .reversed()
                .thenComparing(VectorStore.ScoredEmbedding::id));
        return results.size() > topK
                ? List.copyOf(results.subList(0, topK)) : results;
    }

    private float distanceQueryToNode(float[] queryUnit, Node node) {
        if (node.zero || queryUnit.length != node.dim || node.dim == 0) {
            return Float.POSITIVE_INFINITY;
        }
        if (quantizationMode == QuantizationMode.NONE) {
            return exactDistance(queryUnit, node.unitValues);
        }
        float dot = node.sq8Scale * kernel.dotProductFloatSq8(queryUnit, node.sq8Values);
        return clampCosineDistance(dot);
    }

    private float distanceNodeToNode(Node a, Node b) {
        if (a.zero || b.zero || a.dim != b.dim || a.dim == 0) {
            return Float.POSITIVE_INFINITY;
        }
        if (quantizationMode == QuantizationMode.NONE) {
            return exactDistance(a.unitValues, b.unitValues);
        }
        float dot = (a.sq8Scale * b.sq8Scale) * kernel.dotProductSq8(a.sq8Values, b.sq8Values);
        return clampCosineDistance(dot);
    }

    private float exactDistance(float[] unitA, float[] unitB) {
        return clampCosineDistance(kernel.dotProduct(unitA, unitB));
    }

    private static float clampCosineDistance(float dot) {
        if (dot > 1.0f) {
            return 0.0f;
        }
        if (dot < -1.0f) {
            return 2.0f;
        }
        return 1.0f - dot;
    }

    private void sortCandidatesWithIdTieBreak(int[] indices, float[] dists, int count) {
        for (int i = 1; i < count; i++) {
            int idx = indices[i];
            float d = dists[i];
            String id = nodes.get(idx).id;
            int j = i - 1;
            while (j >= 0 && (dists[j] > d
                    || (dists[j] == d && nodes.get(indices[j]).id.compareTo(id) > 0))) {
                indices[j + 1] = indices[j];
                dists[j + 1] = dists[j];
                j--;
            }
            indices[j + 1] = idx;
            dists[j + 1] = d;
        }
    }

    private void tieBreakEqualDistancesById(int[] indices, float[] dists, int count) {
        int i = 0;
        while (i < count - 1) {
            if (dists[i] != dists[i + 1]) {
                i++;
                continue;
            }
            int end = i + 2;
            while (end < count && dists[end] == dists[i]) {
                end++;
            }
            for (int p = i + 1; p < end; p++) {
                int idx = indices[p];
                String id = nodes.get(idx).id;
                int j = p - 1;
                while (j >= i && nodes.get(indices[j]).id.compareTo(id) > 0) {
                    indices[j + 1] = indices[j];
                    j--;
                }
                indices[j + 1] = idx;
            }
            i = end;
        }
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

    private static boolean isZero(float[] values) {
        if (values == null || values.length == 0) {
            return true;
        }
        for (float value : values) {
            if (value != 0.0f) {
                return false;
            }
        }
        return true;
    }

    private static float[] normalizeCopy(float[] values) {
        int dim = values.length;
        double normSq = 0.0;
        for (int i = 0; i < dim; i++) {
            double v = values[i];
            normSq += v * v;
        }
        float invNorm = (float) (1.0 / Math.sqrt(normSq));
        float[] unit = new float[dim];
        for (int i = 0; i < dim; i++) {
            unit[i] = values[i] * invNorm;
        }
        return unit;
    }

    private static float[] normalizeIfNeeded(float[] values) {
        int dim = values.length;
        double normSq = 0.0;
        for (int i = 0; i < dim; i++) {
            double v = values[i];
            normSq += v * v;
        }
        if (Math.abs(normSq - 1.0) < 1e-5) {
            return values;
        }
        float invNorm = (float) (1.0 / Math.sqrt(normSq));
        float[] unit = new float[dim];
        for (int i = 0; i < dim; i++) {
            unit[i] = values[i] * invNorm;
        }
        return unit;
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class Node {
        private final String id;
        private final int dim;
        private final int level;
        private final boolean zero;
        private float[] rawValues;
        private final float[] unitValues;
        private final byte[] sq8Values;
        private final float sq8Scale;
        private final int[][] neighbors;
        private final int[] neighborCounts;

        private Node(String id, int dim, int level, boolean zero,
                     float[] rawValues, float[] unitValues,
                     byte[] sq8Values, float sq8Scale) {
            this.id = id;
            this.dim = dim;
            this.level = level;
            this.zero = zero;
            this.rawValues = rawValues;
            this.unitValues = unitValues;
            this.sq8Values = sq8Values;
            this.sq8Scale = sq8Scale;
            this.neighbors = new int[level + 1][];
            this.neighborCounts = new int[level + 1];
            for (int i = 0; i <= level; i++) {
                this.neighbors[i] = EMPTY_INTS;
            }
        }

        private void addNeighborUnique(int layer, int neighborIdx, int maxCap) {
            int count = neighborCounts[layer];
            int[] nbrs = neighbors[layer];
            for (int i = 0; i < count; i++) {
                if (nbrs[i] == neighborIdx) {
                    return;
                }
            }
            if (count == nbrs.length) {
                int newLen = nbrs.length == 0
                        ? Math.min(maxCap, 8)
                        : Math.min(maxCap, nbrs.length << 1);
                if (newLen <= count) {
                    newLen = count + 1;
                }
                nbrs = Arrays.copyOf(nbrs, newLen);
                neighbors[layer] = nbrs;
            }
            nbrs[count] = neighborIdx;
            neighborCounts[layer] = count + 1;
        }
    }

    /** Per-thread reusable primitive search workspace (zero boxing on hot search path). */
    private static final class SearchWorkspace {
        private int[] visitedGen = new int[1024];
        private int generation = 1;
        private final IntFloatMinHeap candidates = new IntFloatMinHeap();
        private final IntFloatMaxHeap results = new IntFloatMaxHeap();
        private int[] outIndices = new int[128];
        private float[] outDistances = new float[128];
        private float[] queryUnit = new float[64];
        private float[] shrinkDists = new float[64];

        void resetVisited(int nodeCount) {
            if (visitedGen.length < nodeCount) {
                int next = Math.max(nodeCount, visitedGen.length << 1);
                visitedGen = new int[next];
                generation = 1;
            } else if (generation == Integer.MAX_VALUE) {
                Arrays.fill(visitedGen, 0);
                generation = 1;
            } else {
                generation++;
            }
        }

        boolean markVisited(int nodeIdx) {
            if (visitedGen[nodeIdx] == generation) {
                return false;
            }
            visitedGen[nodeIdx] = generation;
            return true;
        }

        void ensureOutputCapacity(int count) {
            if (outIndices.length < count) {
                int next = Math.max(count, outIndices.length << 1);
                outIndices = new int[next];
                outDistances = new float[next];
            }
        }

        float[] ensureShrinkBuffer(int count) {
            if (shrinkDists.length < count) {
                shrinkDists = new float[Math.max(count, shrinkDists.length << 1)];
            }
            return shrinkDists;
        }

        float[] normalizeQuery(float[] query) {
            int dim = query.length;
            double normSq = 0.0;
            for (int i = 0; i < dim; i++) {
                double v = query[i];
                normSq += v * v;
            }
            if (normSq == 0.0) {
                return null;
            }
            if (queryUnit.length != dim) {
                queryUnit = new float[dim];
            }
            float invNorm = (float) (1.0 / Math.sqrt(normSq));
            for (int i = 0; i < dim; i++) {
                queryUnit[i] = query[i] * invNorm;
            }
            return queryUnit;
        }
    }

    /** Primitive min-heap ordered by ascending float distance. */
    private static final class IntFloatMinHeap {
        private int[] indices = new int[64];
        private float[] dists = new float[64];
        private int size;

        void clear() {
            size = 0;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(int index, float dist) {
            if (size == indices.length) {
                int next = indices.length << 1;
                indices = Arrays.copyOf(indices, next);
                dists = Arrays.copyOf(dists, next);
            }
            int i = size++;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (dists[parent] <= dist) {
                    break;
                }
                indices[i] = indices[parent];
                dists[i] = dists[parent];
                i = parent;
            }
            indices[i] = index;
            dists[i] = dist;
        }

        int peekIndex() {
            return indices[0];
        }

        float peekDist() {
            return dists[0];
        }

        void pop() {
            int last = --size;
            if (last <= 0) {
                return;
            }
            int idx = indices[last];
            float d = dists[last];
            int i = 0;
            int half = size >>> 1;
            while (i < half) {
                int child = (i << 1) + 1;
                int right = child + 1;
                if (right < size && dists[right] < dists[child]) {
                    child = right;
                }
                if (d <= dists[child]) {
                    break;
                }
                indices[i] = indices[child];
                dists[i] = dists[child];
                i = child;
            }
            indices[i] = idx;
            dists[i] = d;
        }
    }

    /** Primitive max-heap ordered by descending float distance (tracks worst top-ef candidate). */
    private static final class IntFloatMaxHeap {
        private int[] indices = new int[64];
        private float[] dists = new float[64];
        private int size;

        void clear() {
            size = 0;
        }

        int size() {
            return size;
        }

        void push(int index, float dist) {
            if (size == indices.length) {
                int next = indices.length << 1;
                indices = Arrays.copyOf(indices, next);
                dists = Arrays.copyOf(dists, next);
            }
            int i = size++;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (dists[parent] >= dist) {
                    break;
                }
                indices[i] = indices[parent];
                dists[i] = dists[parent];
                i = parent;
            }
            indices[i] = index;
            dists[i] = dist;
        }

        int peekIndex() {
            return indices[0];
        }

        float peekDist() {
            return dists[0];
        }

        void replaceTop(int index, float dist) {
            int i = 0;
            int half = size >>> 1;
            while (i < half) {
                int child = (i << 1) + 1;
                int right = child + 1;
                if (right < size && dists[right] > dists[child]) {
                    child = right;
                }
                if (dist >= dists[child]) {
                    break;
                }
                indices[i] = indices[child];
                dists[i] = dists[child];
                i = child;
            }
            indices[i] = index;
            dists[i] = dist;
        }

        void pop() {
            int last = --size;
            if (last <= 0) {
                return;
            }
            int idx = indices[last];
            float d = dists[last];
            int i = 0;
            int half = size >>> 1;
            while (i < half) {
                int child = (i << 1) + 1;
                int right = child + 1;
                if (right < size && dists[right] > dists[child]) {
                    child = right;
                }
                if (d >= dists[child]) {
                    break;
                }
                indices[i] = indices[child];
                dists[i] = dists[child];
                i = child;
            }
            indices[i] = idx;
            dists[i] = d;
        }
    }
}
