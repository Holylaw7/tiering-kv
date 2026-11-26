package io.tieringkv.vector.hnsw;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Pluggable SIMD / FMA vector distance kernel for HNSW graph search (B4).
 *
 * <p>Provides a pure JDK 21 4-way unrolled FMA implementation ({@link ScalarFmaKernel})
 * that HotSpot C2 SuperWord auto-vectorizes to AVX2/AVX-512/NEON instructions with zero
 * extra JVM flags, plus a safe runtime probe for the optional JDK Incubator Vector module
 * ({@code --add-modules=jdk.incubator.vector}) modeled after Apache Lucene's
 * {@code VectorizationProvider} pattern.
 */
public interface VectorDistanceKernel {

    /** Human-readable kernel identifier for diagnostics and benchmarks. */
    String name();

    /** Dot product of two equal-length float32 vectors. */
    float dotProduct(float[] a, float[] b);

    /** Asymmetric dot product of a float32 query vector and an SQ8 quantized byte vector. */
    float dotProductFloatSq8(float[] query, byte[] quantized);

    /** Symmetric integer dot product of two SQ8 quantized byte vectors. */
    int dotProductSq8(byte[] a, byte[] b);

    /** Returns the active default kernel detected for the current JVM runtime. */
    static VectorDistanceKernel active() {
        return Holder.ACTIVE;
    }

    /** Returns the built-in 4-way unrolled FMA scalar kernel. */
    static VectorDistanceKernel scalarFma() {
        return ScalarFmaKernel.INSTANCE;
    }

    /** Whether {@code jdk.incubator.vector} is present in the boot module layer. */
    static boolean isPanamaVectorModulePresent() {
        return Holder.PANAMA_PRESENT;
    }

    /**
     * Preferred SIMD bit width reported by {@code FloatVector.SPECIES_PREFERRED} when
     * {@code jdk.incubator.vector} is enabled, or {@code 0} when unavailable.
     */
    static int panamaPreferredBitSize() {
        return Holder.PANAMA_BIT_SIZE;
    }

    /**
     * Pure JDK 21 4-way unrolled {@link Math#fma} kernel.
     *
     * <p>Four independent accumulators break loop-carried FMA latency chains and enable
     * HotSpot C2 SuperWord vectorization without requiring incubator JVM flags.
     */
    final class ScalarFmaKernel implements VectorDistanceKernel {

        static final ScalarFmaKernel INSTANCE = new ScalarFmaKernel();

        private ScalarFmaKernel() {
        }

        @Override
        public String name() {
            return "scalar-fma4";
        }

        @Override
        public float dotProduct(float[] a, float[] b) {
            int len = Math.min(a.length, b.length);
            int i = 0;
            float s0 = 0.0f;
            float s1 = 0.0f;
            float s2 = 0.0f;
            float s3 = 0.0f;
            int limit = len & ~3;
            for (; i < limit; i += 4) {
                s0 = Math.fma(a[i], b[i], s0);
                s1 = Math.fma(a[i + 1], b[i + 1], s1);
                s2 = Math.fma(a[i + 2], b[i + 2], s2);
                s3 = Math.fma(a[i + 3], b[i + 3], s3);
            }
            float sum = (s0 + s1) + (s2 + s3);
            for (; i < len; i++) {
                sum = Math.fma(a[i], b[i], sum);
            }
            return sum;
        }

        @Override
        public float dotProductFloatSq8(float[] query, byte[] quantized) {
            int len = Math.min(query.length, quantized.length);
            int i = 0;
            float s0 = 0.0f;
            float s1 = 0.0f;
            float s2 = 0.0f;
            float s3 = 0.0f;
            int limit = len & ~3;
            for (; i < limit; i += 4) {
                s0 = Math.fma(query[i], quantized[i], s0);
                s1 = Math.fma(query[i + 1], quantized[i + 1], s1);
                s2 = Math.fma(query[i + 2], quantized[i + 2], s2);
                s3 = Math.fma(query[i + 3], quantized[i + 3], s3);
            }
            float sum = (s0 + s1) + (s2 + s3);
            for (; i < len; i++) {
                sum = Math.fma(query[i], quantized[i], sum);
            }
            return sum;
        }

        @Override
        public int dotProductSq8(byte[] a, byte[] b) {
            int len = Math.min(a.length, b.length);
            int i = 0;
            int s0 = 0;
            int s1 = 0;
            int s2 = 0;
            int s3 = 0;
            int limit = len & ~3;
            for (; i < limit; i += 4) {
                s0 += a[i] * b[i];
                s1 += a[i + 1] * b[i + 1];
                s2 += a[i + 2] * b[i + 2];
                s3 += a[i + 3] * b[i + 3];
            }
            int sum = (s0 + s1) + (s2 + s3);
            for (; i < len; i++) {
                sum += a[i] * b[i];
            }
            return sum;
        }
    }

    /** Lazy holder performing one-time safe reflection probe for Panama Vector API. */
    final class Holder {
        static final boolean PANAMA_PRESENT;
        static final int PANAMA_BIT_SIZE;
        static final VectorDistanceKernel ACTIVE;

        static {
            int detectedBits = probePanamaBitSize();
            PANAMA_PRESENT = detectedBits > 0;
            PANAMA_BIT_SIZE = detectedBits;
            ACTIVE = ScalarFmaKernel.INSTANCE;
        }

        private Holder() {
        }

        private static int probePanamaBitSize() {
            if (ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
                return 0;
            }
            try {
                Class<?> floatVectorClass = Class.forName("jdk.incubator.vector.FloatVector");
                Field speciesField = floatVectorClass.getField("SPECIES_PREFERRED");
                Object species = speciesField.get(null);
                Method bitSizeMethod = species.getClass().getMethod("vectorBitSize");
                Object result = bitSizeMethod.invoke(species);
                return result instanceof Integer bits ? bits : 0;
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return 0;
            }
        }
    }
}
