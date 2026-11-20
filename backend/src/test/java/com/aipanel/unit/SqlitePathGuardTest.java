package com.aipanel.unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aipanel.config.DataSourceSecurityException;
import com.aipanel.config.GuardedSqliteDataSource;
import com.aipanel.config.SqlitePathGuard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P05-DB-ISOLATION 的单元部分：守卫在打开 SQLite 之前拒绝越界路径。 */
class SqlitePathGuardTest {

    @Test
    @DisplayName("允许目录内的数据库路径通过校验并被规范化")
    void acceptsPathInsideAllowedRoot(@TempDir Path root) throws IOException {
        Path allowed = Files.createDirectories(root.resolve("allowed"));
        Path database = allowed.resolve("panel.db");

        Path resolved = SqlitePathGuard.resolve(database.toString(), allowed.toString());

        assertThat(resolved).isEqualTo(database.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("允许目录尚未创建时仍可解析其下方的数据库路径")
    void acceptsPathUnderMissingAllowedRoot(@TempDir Path root) {
        Path allowed = root.resolve("not-created-yet");
        Path database = allowed.resolve("panel.db");

        Path resolved = SqlitePathGuard.resolve(database.toString(), allowed.toString());

        assertThat(resolved).isEqualTo(database.toAbsolutePath().normalize());
        assertThat(allowed).doesNotExist();
    }

    @Test
    @DisplayName("拒绝允许目录之外的普通路径")
    void rejectsPathOutsideAllowedRoot(@TempDir Path root) throws IOException {
        Path allowed = Files.createDirectories(root.resolve("allowed"));
        Path outside = root.resolve("business.db");

        assertThatThrownBy(() -> SqlitePathGuard.resolve(outside.toString(), allowed.toString()))
                .isInstanceOf(DataSourceSecurityException.class)
                .hasMessageContaining("越界");
        assertThat(outside).doesNotExist();
    }

    @Test
    @DisplayName("规范化后越界的 .. 路径被拒绝")
    void rejectsTraversalAfterNormalization(@TempDir Path root) throws IOException {
        Path allowed = Files.createDirectories(root.resolve("allowed"));
        String traversal = allowed.resolve("..").resolve("escaped.db").toString();

        assertThatThrownBy(() -> SqlitePathGuard.resolve(traversal, allowed.toString()))
                .isInstanceOf(DataSourceSecurityException.class)
                .hasMessageContaining("越界");
    }

    @Test
    @DisplayName("经目录符号链接逃逸的路径被拒绝")
    void rejectsSymlinkEscape(@TempDir Path root) throws IOException {
        Path allowed = Files.createDirectories(root.resolve("allowed"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path link = allowed.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            Assumptions.assumeTrue(false, "当前文件系统不支持创建符号链接，跳过该用例");
        }

        assertThatThrownBy(() -> SqlitePathGuard.resolve(link.resolve("panel.db").toString(), allowed.toString()))
                .isInstanceOf(DataSourceSecurityException.class);
    }

    @Test
    @DisplayName("拒绝连接串形式的路径")
    void rejectsJdbcUrl(@TempDir Path root) {
        assertThatThrownBy(() -> SqlitePathGuard.resolve("jdbc:sqlite:" + root.resolve("db").toString(), root.toString()))
                .isInstanceOf(DataSourceSecurityException.class)
                .hasMessageContaining("连接串");
    }

    @Test
    @DisplayName("越界数据源在打开 SQLite 之前失败，连接次数为 0")
    void blocksBeforeOpeningSqlite(@TempDir Path root) throws IOException {
        Path allowed = Files.createDirectories(root.resolve("allowed"));
        Path outside = root.resolve("outside").resolve("panel.db");
        GuardedSqliteDataSource dataSource = new GuardedSqliteDataSource(outside, allowed);
        GuardedSqliteDataSource.resetConnectionsOpened();

        assertThatThrownBy(() -> {
            try (Connection ignored = dataSource.getConnection()) {
                // 不应到达此处。
            }
        }).isInstanceOf(DataSourceSecurityException.class);

        assertThat(GuardedSqliteDataSource.connectionsOpened()).isZero();
        assertThat(outside).doesNotExist();
    }
}