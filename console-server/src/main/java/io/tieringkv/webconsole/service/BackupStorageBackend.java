package io.tieringkv.webconsole.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * 备份文件存储后端 SPI（M3-B2）：默认本地/共享卷实现，后续可直接接入 S3 / MinIO 对象存储。
 */
public interface BackupStorageBackend {

    /** 校验并解析本地路径（对象存储实现可回落到缓存/流式下载）。 */
    Path resolvePath(String fileName);

    /** 打开临时写入流（写入完成后由 {@link #publish(String)} 原子发布）。 */
    OutputStream openTempOutput(String fileName) throws IOException;

    /** 原子发布临时文件到正式对象名，并返回发布后的字节大小。 */
    long publish(String fileName) throws IOException;

    /** 清理失败残留的临时文件。 */
    void deleteTempQuietly(String fileName);

    /** 打开读取流。 */
    InputStream openInput(String fileName) throws IOException;

    boolean exists(String fileName);

    boolean deleteIfExists(String fileName) throws IOException;

    Path rootDirectory();
}
