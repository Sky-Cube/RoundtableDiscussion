package com.aipanel.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/** 测试目录：每次运行在 target/test-db 下新建独立目录，避免接触业务库。 */
public final class TestDirectories {

    private TestDirectories() {
    }

    public static Path create(String prefix) {
        try {
            Path directory = Paths.get("target", "test-db", prefix + "-" + UUID.randomUUID())
                    .toAbsolutePath().normalize();
            Files.createDirectories(directory);
            return directory;
        } catch (IOException error) {
            throw new IllegalStateException("无法创建测试目录", error);
        }
    }

    public static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不影响测试结论。
                }
            });
        } catch (IOException ignored) {
            // 同上。
        }
    }
}