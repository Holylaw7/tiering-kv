package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * BIT 命令族（ADR-0334）：SETBIT/GETBIT/BITCOUNT/BITPOS/BITOP。
 *
 * <p>位图即字符串（Redis 语义）：大端字节序，字节内高位在前
 * （offset 的位位置 = 7 - offset%8）；SETBIT 零字节扩展；
 * BITOP 缺失源按零串处理，AND/OR/XOR 结果长度为最长源。
 */
public final class BitCommand implements Command {

    private static final long MAX_BIT_OFFSET = (1L << 32) - 1;

    private final String name;

    public BitCommand(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        return switch (name) {
            case "setbit" -> setbit(args, storage);
            case "getbit" -> getbit(args, storage);
            case "bitcount" -> bitcount(args, storage);
            case "bitpos" -> bitpos(args, storage);
            case "bitop" -> bitop(args, storage);
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue setbit(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        long offset;
        long bit;
        try {
            offset = CommandUtil.parseLong(args.get(1));
            bit = CommandUtil.parseLong(args.get(2));
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        }
        if (offset < 0 || offset > MAX_BIT_OFFSET) {
            return new RespError(
                    "ERR bit offset is not an integer or out of range");
        }
        if (bit != 0 && bit != 1) {
            return new RespError(
                    "ERR bit is not an integer or out of range");
        }
        long byteIndex = offset / 8;
        if (byteIndex >= Integer.MAX_VALUE) {
            return new RespError(
                    "ERR string exceeds maximum allowed size");
        }
        int bitPos = (int) (7 - offset % 8);
        byte[] oldBitHolder = new byte[1];
        try {
            TypeSupport.update(storage, args.get(0), current -> {
                if (current != null && TypedValueCodec.typeOf(current)
                        != ValueType.STRING) {
                    throw TypeSupport.wrongTypeException();
                }
                int needed = (int) byteIndex + 1;
                byte[] result = current == null ? new byte[needed]
                        : Arrays.copyOf(current, Math.max(
                        current.length, needed));
                int mask = 1 << bitPos;
                oldBitHolder[0] = (byte) ((result[(int) byteIndex]
                        >> bitPos) & 1);
                if (bit == 1) {
                    result[(int) byteIndex] |= mask;
                } else {
                    result[(int) byteIndex] &= ~mask;
                }
                return result;
            });
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
        return new RespInteger(oldBitHolder[0]);
    }

    private RespValue getbit(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name);
        }
        long offset;
        try {
            offset = CommandUtil.parseLong(args.get(1));
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        }
        if (offset < 0 || offset > MAX_BIT_OFFSET) {
            return new RespError(
                    "ERR bit offset is not an integer or out of range");
        }
        try {
            byte[] value = stringValue(storage, args.get(0));
            if (value == null) {
                return new RespInteger(0);
            }
            long byteIndex = offset / 8;
            if (byteIndex >= value.length) {
                return new RespInteger(0);
            }
            int bitPos = (int) (7 - offset % 8);
            return new RespInteger((value[(int) byteIndex] >> bitPos)
                    & 1);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue bitcount(List<byte[]> args,
                               StorageEngine storage) {
        if (args.size() != 1 && args.size() != 3
                && args.size() != 4) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] value = stringValue(storage, args.get(0));
            if (value == null) {
                return new RespInteger(0);
            }
            boolean bitMode = false;
            if (args.size() == 4) {
                String mode = CommandUtil.text(args.get(3))
                        .toLowerCase(Locale.ROOT);
                if (mode.equals("bit")) {
                    bitMode = true;
                } else if (!mode.equals("byte")) {
                    return new RespError("ERR syntax error");
                }
            }
            long totalBits = value.length * 8L;
            long start = 0;
            long end = totalBits - 1;
            if (args.size() >= 3) {
                long unitLength = bitMode ? totalBits : value.length;
                start = normalizeIndex(
                        CommandUtil.parseLong(args.get(1)),
                        unitLength);
                end = normalizeIndex(
                        CommandUtil.parseLong(args.get(2)),
                        unitLength);
                if (!bitMode) {
                    start = start * 8;
                    end = end * 8 + 7;
                }
            }
            if (start > end || start >= totalBits) {
                return new RespInteger(0);
            }
            end = Math.min(end, totalBits - 1);
            long count = 0;
            for (long bit = start; bit <= end; bit++) {
                count += (value[(int) (bit / 8)]
                        >> (7 - (bit % 8))) & 1;
            }
            return new RespInteger(count);
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue bitpos(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() < 2 || args.size() > 5) {
            return RespError.wrongArity(name);
        }
        long bit;
        try {
            bit = CommandUtil.parseLong(args.get(1));
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        }
        if (bit != 0 && bit != 1) {
            return new RespError(
                    "ERR The bit argument must be 1 or 0.");
        }
        try {
            byte[] value = stringValue(storage, args.get(0));
            boolean hasRange = args.size() >= 3;
            if (value == null) {
                if (bit == 1 || hasRange) {
                    return new RespInteger(-1);
                }
                return new RespInteger(0);
            }
            boolean bitMode = false;
            if (args.size() == 5) {
                String mode = CommandUtil.text(args.get(4))
                        .toLowerCase(Locale.ROOT);
                if (mode.equals("bit")) {
                    bitMode = true;
                } else if (!mode.equals("byte")) {
                    return new RespError("ERR syntax error");
                }
            }
            long totalBits = value.length * 8L;
            long startBit;
            long endBit;
            if (!hasRange) {
                startBit = 0;
                endBit = totalBits - 1;
            } else {
                long unitLength = bitMode ? totalBits : value.length;
                startBit = normalizeIndex(
                        CommandUtil.parseLong(args.get(2)),
                        unitLength);
                endBit = args.size() >= 4
                        ? normalizeIndex(
                        CommandUtil.parseLong(args.get(3)),
                        unitLength)
                        : unitLength - 1;
                if (!bitMode) {
                    startBit *= 8;
                    endBit = endBit * 8 + 7;
                }
                if (startBit > endBit || startBit >= totalBits) {
                    return new RespInteger(-1);
                }
            }
            endBit = Math.min(endBit, totalBits - 1);
            for (long position = startBit; position <= endBit;
                 position++) {
                int current = (value[(int) (position / 8)]
                        >> (7 - (position % 8))) & 1;
                if (current == bit) {
                    return new RespInteger(position);
                }
            }
            if (!hasRange && bit == 0) {
                return new RespInteger(totalBits);
            }
            return new RespInteger(-1);
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue bitop(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() < 3) {
            return RespError.wrongArity(name);
        }
        String op = CommandUtil.text(args.get(0))
                .toLowerCase(Locale.ROOT);
        if (!op.equals("and") && !op.equals("or")
                && !op.equals("xor") && !op.equals("not")) {
            return new RespError("ERR syntax error");
        }
        if (op.equals("not") && args.size() != 3) {
            return new RespError("ERR BITOP NOT must be called "
                    + "with a single source key.");
        }
        try {
            List<byte[]> sources = new ArrayList<>();
            for (int i = 2; i < args.size(); i++) {
                byte[] value = stringValue(storage, args.get(i));
                sources.add(value == null ? new byte[0] : value);
            }
            int length = 0;
            for (byte[] source : sources) {
                length = Math.max(length, source.length);
            }
            byte[] result = new byte[length];
            for (int i = 0; i < length; i++) {
                if (op.equals("not")) {
                    result[i] = (byte) ~sources.get(0)[i];
                    continue;
                }
                int acc = op.equals("and") ? 0xff : 0;
                for (byte[] source : sources) {
                    int value = i < source.length
                            ? (source[i] & 0xff) : 0;
                    acc = switch (op) {
                        case "and" -> acc & value;
                        case "or" -> acc | value;
                        case "xor" -> acc ^ value;
                        default -> acc;
                    };
                }
                result[i] = (byte) acc;
            }
            storage.put(args.get(1), result);
            return new RespInteger(length);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private static byte[] stringValue(StorageEngine storage,
                                      byte[] key) {
        byte[] value = storage.get(key);
        if (value != null && TypedValueCodec.typeOf(value)
                != ValueType.STRING) {
            throw TypeSupport.wrongTypeException();
        }
        return value;
    }

    private static long normalizeIndex(long index, long length) {
        if (index < 0) {
            index += length;
        }
        return Math.max(0, Math.min(index, length - 1));
    }
}
