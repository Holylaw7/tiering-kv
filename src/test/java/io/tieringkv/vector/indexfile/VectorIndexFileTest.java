package io.tieringkv.vector.indexfile;

import io.tieringkv.vector.Embedding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 向量索引文件格式（ADR-0319）：roundtrip / CRC / 版本 / 原子写。 */
class VectorIndexFileTest {

    @TempDir
    Path dir;

    private static VectorIndexFile.IndexData data(int count, int dim) {
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float[] values = new float[dim];
            for (int d = 0; d < dim; d++) {
                values[d] = (i + d) * 0.1f;
            }
            embeddings.add(new Embedding("id-" + i, values));
        }
        return new VectorIndexFile.IndexData(4, dim, embeddings);
    }

    @Test
    void encodeDecodeRoundTrip() throws Exception {
        VectorIndexFile.IndexData data = data(20, 8);
        VectorIndexFile.IndexData restored =
                VectorIndexFile.decode(VectorIndexFile.encode(data));
        assertThat(restored.maxLevel()).isEqualTo(4);
        assertThat(restored.dim()).isEqualTo(8);
        assertThat(restored.embeddings()).hasSize(20);
        assertThat(restored.embeddings().get(0).id())
                .isEqualTo("id-0");
        assertThat(restored.embeddings().get(19).values()[7])
                .isEqualTo(26 * 0.1f);
    }

    @Test
    void emptyIndexRoundTrip() throws Exception {
        VectorIndexFile.IndexData data =
                new VectorIndexFile.IndexData(3, 0, List.of());
        VectorIndexFile.IndexData restored =
                VectorIndexFile.decode(VectorIndexFile.encode(data));
        assertThat(restored.embeddings()).isEmpty();
        assertThat(restored.maxLevel()).isEqualTo(3);
    }

    @Test
    void corruptedPayloadRejectedByCrc() throws Exception {
        byte[] encoded = VectorIndexFile.encode(data(5, 4));
        encoded[encoded.length - 5] ^= 0x7F; // 破坏 payload 末字节
        assertThatThrownBy(() -> VectorIndexFile.decode(encoded))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("CRC");
    }

    @Test
    void wrongMagicRejected() throws Exception {
        byte[] encoded = VectorIndexFile.encode(data(1, 2));
        encoded[0] = 'X';
        assertThatThrownBy(() -> VectorIndexFile.decode(encoded))
                .hasMessageContaining("magic");
    }

    @Test
    void writeReadRoundTripIsAtomic() throws Exception {
        Path file = dir.resolve("vector-index.tvif");
        VectorIndexFile.write(file, data(50, 16));
        VectorIndexFile.IndexData restored =
                VectorIndexFile.read(file);
        assertThat(restored.embeddings()).hasSize(50);
        // 原子写：无残留临时文件
        try (var stream = Files.list(dir)) {
            assertThat(stream.map(Path::getFileName)
                    .map(Path::toString)
                    .noneMatch(name -> name.endsWith(".tmp")))
                    .isTrue();
        }
    }

    @Test
    void overwriteReplacesFile() throws Exception {
        Path file = dir.resolve("vector-index.tvif");
        VectorIndexFile.write(file, data(10, 4));
        VectorIndexFile.write(file, data(30, 4));
        assertThat(VectorIndexFile.read(file).embeddings())
                .hasSize(30);
    }
}
