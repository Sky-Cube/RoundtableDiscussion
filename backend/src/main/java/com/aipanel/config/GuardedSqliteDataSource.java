package com.aipanel.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.sqlite.SQLiteConfig;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * 受守卫的 SQLite 数据源：每次打开连接前复核路径，并开启外键、WAL 与写锁等待。
 */
public final class GuardedSqliteDataSource extends AbstractDataSource {

    private final Path database;
    private final Path allowedRoot;
    private final String url;
    private final SQLiteConfig config;

    private static final java.util.concurrent.atomic.AtomicInteger CONNECTIONS_OPENED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 实际调用 SQLite 驱动打开连接的次数，供隔离用例证明守卫在打开之前拒绝。 */
    public static int connectionsOpened() {
        return CONNECTIONS_OPENED.get();
    }

    public static void resetConnectionsOpened() {
        CONNECTIONS_OPENED.set(0);
    }

    public GuardedSqliteDataSource(Path database, Path allowedRoot) {
        this.database = database.toAbsolutePath().normalize();
        this.allowedRoot = allowedRoot.toAbsolutePath().normalize();
        this.url = "jdbc:sqlite:" + this.database.toString().replace('\\', '/');
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setBusyTimeout(5_000);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        this.config = sqliteConfig;
    }

    /** 对外暴露的 JDBC URL，仅用于启动日志与隔离断言。 */
    public String jdbcUrl() {
        return url;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return open();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return open();
    }

    private Connection open() throws SQLException {
        SqlitePathGuard.verify(database, allowedRoot);
        Path directory = database.getParent();
        if (directory != null) {
            try {
                Files.createDirectories(directory);
            } catch (java.io.IOException error) {
                throw new SQLException("无法创建数据库目录：" + directory, error);
            }
        }
        Connection connection = java.sql.DriverManager.getConnection(url, config.toProperties());
        CONNECTIONS_OPENED.incrementAndGet();
        try (Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA foreign_keys")) {
                if (!rows.next() || rows.getInt(1) != 1) {
                    throw new SQLException("连接未启用外键约束");
                }
            }
            try (var rows = statement.executeQuery("PRAGMA busy_timeout")) {
                if (!rows.next() || rows.getInt(1) <= 0) {
                    throw new SQLException("连接未设置 busy_timeout");
                }
            }
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }
}
