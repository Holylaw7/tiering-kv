package io.tieringkv.protocol;

/**
 * RESP2 协议值类型（ADR-0006）。
 * sealed 封闭类型集合，Decoder / Encoder 可穷举处理，避免隐式状态。
 */
public sealed interface RespValue
        permits RespSimpleString, RespError, RespInteger,
        RespBulkString, RespArray, RespNull, RespMap, RespSet,
        RespDouble, RespBigNumber, RespPush {
}
