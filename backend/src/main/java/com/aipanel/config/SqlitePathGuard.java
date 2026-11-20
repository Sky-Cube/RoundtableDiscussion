package com.aipanel.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SQLite 路径守卫。
 *
 * <p>在打开任何连接之前校验目标库位于允许目录内。越界路径、连接串以及经符号链接逃逸的目录
 * 都会被直接拒绝，不产生文件、不建立连接。
 */
public final class SqlitePathGuard {

    private SqlitePathGuard() {
    }

    /** 解析并校验数据库文件路径，返回规范化后的绝对路径。 */
    public static Path resolve(String rawPath, String rawAllowedRoot) {
        String databaseText = requireText(rawPath, "数据库路径");
        String rootText = requireText(rawAllowedRoot, "数据库允许目录");
        if (databaseText.contains("://") || databaseText.startsWith("jdbc:") || databaseText.startsWith("classpath:")) {
            throw new DataSourceSecurityException("数据库路径必须是文件路径，不能是连接串或 classpath 资源：" + databaseText);
        }
        Path root = Paths.get(rootText).toAbsolutePath().normalize();
        Path database = Paths.get(databaseText).toAbsolutePath().normalize();
        verify(database, root);
        return database;
    }

    /** 校验已解析的数据库路径仍位于允许目录内，用于每次打开连接前的复核。 */
    public static void verify(Path database, Path allowedRoot) {
        Path root = allowedRoot.toAbsolutePath().normalize();
        Path target = database.toAbsolutePath().normalize();
        if (target.equals(root)) {
            throw new DataSourceSecurityException("数据库路径不能等于允许目录本身：" + target);
        }
        if (!target.startsWith(root)) {
            throw new DataSourceSecurityException("数据库路径越界，必须位于 " + root + " 之内：" + target);
        }
        try {
            Path realParent = realPathOf(target.getParent());
            Path realRoot = realPathOf(root);
            if (!realParent.startsWith(realRoot)) {
                throw new DataSourceSecurityException("数据库路径经符号链接越界：" + realParent);
            }
        } catch (IOException error) {
            throw new DataSourceSecurityException("无法解析数据库目录的真实路径：" + error.getMessage());
        }
    }

    /**
     * 解析路径的真实形式。允许目录或数据库尚不存在时，逐级回退到最近的存在目录再补回缺失段，
     * 这样首次启动（目录还没创建）不会被误判为越界。
     */
    private static Path realPathOf(Path path) throws IOException {
        java.util.ArrayDeque<Path> missing = new java.util.ArrayDeque<>();
        Path cursor = path.toAbsolutePath().normalize();
        while (cursor != null && !Files.exists(cursor)) {
            missing.push(cursor.getFileName());
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            return path;
        }
        Path real = cursor.toRealPath();
        for (Path segment : missing) {
            if (segment != null) {
                real = real.resolve(segment);
            }
        }
        return real;
    }


    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new DataSourceSecurityException(label + "为空");
        }
        return value.trim();
    }
}
