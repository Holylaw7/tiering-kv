package io.tieringkv.vector;

/** 向量（ADR-0113）：id + float 数组。 */
public record Embedding(String id, float[] values) {

    public Embedding {
        values = values.clone();
    }

    @Override
    public float[] values() {
        return values.clone();
    }
}
