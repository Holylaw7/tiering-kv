package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;

import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

/** 复合类型命令公共支持（ADR-0276）：类型判定 + 原子更新 + WRONGTYPE。 */
final class TypeSupport {

    static final String WRONGTYPE = "WRONGTYPE Operation against "
            + "a key holding the wrong kind of value";

    /** transform 内抛出以回滚并返回 WRONGTYPE。 */
    static final class WrongTypeException extends RuntimeException {
        WrongTypeException() {
            super(WRONGTYPE);
        }
    }

    private TypeSupport() {
    }

    static RespError wrongType() {
        return new RespError(WRONGTYPE);
    }

    static WrongTypeException wrongTypeException() {
        return new WrongTypeException();
    }

    static ValueType typeOf(StorageEngine storage, byte[] key) {
        byte[] value = storage.get(key);
        return value == null ? null
                : TypedValueCodec.typeOf(value);
    }

    /** 原子更新：优先 AtomicStringOps.update；否则 get/put 回退。 */
    static byte[] update(StorageEngine storage, byte[] key,
                         UnaryOperator<byte[]> transform) {
        if (storage instanceof AtomicStringOps atomic) {
            return atomic.update(key, transform);
        }
        byte[] current = storage.get(key);
        byte[] next = transform.apply(current);
        if (next == null) {
            storage.delete(key);
        } else {
            storage.put(key, next);
        }
        return next;
    }

    static double parseDouble(byte[] bytes) {
        return Double.parseDouble(new String(bytes,
                StandardCharsets.UTF_8).trim());
    }

    /** Redis 风格分数格式：整数去尾零。 */
    static String formatScore(double score) {
        if (Double.isInfinite(score)) {
            return score > 0 ? "inf" : "-inf";
        }
        if (score == Math.rint(score)
                && Math.abs(score) < 1e15) {
            return Long.toString((long) score);
        }
        return Double.toString(score);
    }
}
