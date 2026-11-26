package io.tieringkv.vector.hnsw;

/**
 * Per-vector symmetric abs-max SQ8 scalar quantizer for unit-normalized vectors (B4).
 *
 * <p>For each normalized vector {@code u}, computes {@code maxAbs = max_i |u[i]|} and stores
 * {@code scale = maxAbs / 127f} alongside signed bytes {@code q[i] = round(u[i] / scale)} in
 * {@code [-127, +127]}. Retaining full 8-bit dynamic range per vector preserves high cosine
 * recall across arbitrary dimensionalities (64D..1536D) while cutting vector memory by ~75%.
 */
public final class ScalarQuantizerSq8 {

    private static final float MAX_QUANTIZED = 127.0f;

    private ScalarQuantizerSq8() {
    }

    /** Quantizes a unit-normalized float vector into signed 8-bit values and a scale factor. */
    public static QuantizedVector quantizeUnit(float[] unitValues) {
        if (unitValues == null || unitValues.length == 0) {
            return new QuantizedVector(new byte[0], 0.0f);
        }
        int dim = unitValues.length;
        float maxAbs = 0.0f;
        for (int i = 0; i < dim; i++) {
            float abs = Math.abs(unitValues[i]);
            if (abs > maxAbs) {
                maxAbs = abs;
            }
        }
        byte[] quantized = new byte[dim];
        if (maxAbs == 0.0f) {
            return new QuantizedVector(quantized, 0.0f);
        }
        float scale = maxAbs / MAX_QUANTIZED;
        float invScale = MAX_QUANTIZED / maxAbs;
        for (int i = 0; i < dim; i++) {
            int q = Math.round(unitValues[i] * invScale);
            if (q > 127) {
                q = 127;
            } else if (q < -127) {
                q = -127;
            }
            quantized[i] = (byte) q;
        }
        return new QuantizedVector(quantized, scale);
    }

    /**
     * Quantizes a unit-normalized float vector into a caller-provided byte buffer and returns
     * its scale factor (zero allocation).
     */
    public static float quantizeUnitInto(float[] unitValues, byte[] dest) {
        int dim = unitValues.length;
        float maxAbs = 0.0f;
        for (int i = 0; i < dim; i++) {
            float abs = Math.abs(unitValues[i]);
            if (abs > maxAbs) {
                maxAbs = abs;
            }
        }
        if (maxAbs == 0.0f) {
            for (int i = 0; i < dim; i++) {
                dest[i] = 0;
            }
            return 0.0f;
        }
        float scale = maxAbs / MAX_QUANTIZED;
        float invScale = MAX_QUANTIZED / maxAbs;
        for (int i = 0; i < dim; i++) {
            int q = Math.round(unitValues[i] * invScale);
            if (q > 127) {
                q = 127;
            } else if (q < -127) {
                q = -127;
            }
            dest[i] = (byte) q;
        }
        return scale;
    }

    /** Immutable-view wrapper around a quantized SQ8 vector and its scale factor. */
    public static final class QuantizedVector {
        private final byte[] values;
        private final float scale;

        QuantizedVector(byte[] values, float scale) {
            this.values = values;
            this.scale = scale;
        }

        public int dimension() {
            return values.length;
        }

        public float scale() {
            return scale;
        }

        public byte[] copyValues() {
            return values.clone();
        }

        byte[] rawValues() {
            return values;
        }
    }
}
