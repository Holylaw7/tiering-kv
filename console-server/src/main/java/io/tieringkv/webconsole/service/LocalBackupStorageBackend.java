package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** 本地/共享卷备份存储后端：防路径穿越 + 临时文件原子移动发布。 */
@Component
public class LocalBackupStorageBackend implements BackupStorageBackend {

    private static final Logger log = LoggerFactory.getLogger(LocalBackupStorageBackend.class);

    private final Path root;

    @Autowired
    public LocalBackupStorageBackend(ConsoleProperties properties) {
        this(Paths.get(properties.getBackup().getDir()));
    }

    public LocalBackupStorageBackend(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Path resolvePath(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw BusinessException.badRequest("非法备份路径");
        }
        Path target = root.resolve(fileName).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw BusinessException.badRequest("非法备份路径");
        }
        return target;
    }

    private Path resolveTemp(String fileName) {
        return resolvePath(fileName + ".tmp");
    }

    @Override
    public OutputStream openTempOutput(String fileName) throws IOException {
        Files.createDirectories(root);
        return Files.newOutputStream(resolveTemp(fileName));
    }

    @Override
    public long publish(String fileName) throws IOException {
        Path temp = resolveTemp(fileName);
        Path target = resolvePath(fileName);
        long size = Files.size(temp);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            log.warn("文件系统不支持 ATOMIC_MOVE，降级为普通移动：{}", target);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return size;
    }

    @Override
    public void deleteTempQuietly(String fileName) {
        try {
            Files.deleteIfExists(resolveTemp(fileName));
        } catch (IOException | RuntimeException ex) {
            log.warn("删除临时备份文件失败：{}", fileName, ex);
        }
    }

    @Override
    public InputStream openInput(String fileName) throws IOException {
        Path file = resolvePath(fileName);
        if (!Files.exists(file)) {
            throw BusinessException.notFound("备份文件不存在：" + fileName);
        }
        return Files.newInputStream(file);
    }

    @Override
    public boolean exists(String fileName) {
        return Files.exists(resolvePath(fileName));
    }

    @Override
    public boolean deleteIfExists(String fileName) throws IOException {
        return Files.deleteIfExists(resolvePath(fileName));
    }

    @Override
    public Path rootDirectory() {
        return root;
    }
}
