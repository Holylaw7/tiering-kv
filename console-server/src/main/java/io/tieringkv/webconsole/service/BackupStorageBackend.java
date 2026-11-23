package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.OptionalLong;

/**
 * 备份文件存储后端 SPI（M3-B2 + N1-A）：抽象本地/共享卷与 S3 / MinIO 对象存储。
 */
public interface BackupStorageBackend {

    /** 已发布备份对象元数据（用于孤儿清理与远端容量核算）。 */
    record StoredObject(String name, long sizeBytes, long lastModifiedMillis) {
    }

    /** 校验备份文件名：禁止空串、目录分隔符与路径穿越。 */
    default void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()
                || ".".equals(fileName) || "..".equals(fileName)
                || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains("..") || fileName.indexOf('\0') >= 0) {
            throw BusinessException.badRequest("非法备份路径");
        }
    }

    /** 打开临时写入流（写入完成后由 {@link #publish(String)} 发布）。 */
    OutputStream openTempOutput(String fileName) throws IOException;

    /** 发布临时文件到正式对象名，并返回发布后的字节大小。 */
    long publish(String fileName) throws IOException;

    /** 清理失败残留的临时文件。 */
    void deleteTempQuietly(String fileName);

    /** 打开读取流。 */
    InputStream openInput(String fileName) throws IOException;

    boolean exists(String fileName);

    boolean deleteIfExists(String fileName) throws IOException;

    /** 列举当前存储中所有已发布备份对象，供孤儿回收与配额检查使用。 */
    List<StoredObject> listObjects() throws IOException;

    /** 全局容量预留悲观锁锚点标识（本地为目录绝对路径，S3 为 s3://bucket/prefix）。 */
    String storageKey();

    /** 本地目录或 S3 本地暂存目录剩余可用字节数；无法读取时返回 empty。 */
    OptionalLong usableSpaceBytes();

    /** 远端对象存储软配额上限（字节）；未限制时返回 empty。 */
    default OptionalLong remoteQuotaBytes() {
        return OptionalLong.empty();
    }
}
