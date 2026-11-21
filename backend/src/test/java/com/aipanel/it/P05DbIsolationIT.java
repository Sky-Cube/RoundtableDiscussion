package com.aipanel.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import com.aipanel.AiPanelApplication;
import com.aipanel.config.DataSourceSecurityException;
import com.aipanel.config.GuardedSqliteDataSource;
import com.aipanel.support.AbstractPanelIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P05-DB-ISOLATION：只在显式注入的临时库上运行，业务库永不被打开。 */
class P05DbIsolationIT extends AbstractPanelIntegrationTest {

    /** 类加载早于 Spring 上下文启动，用于判断业务库是否被本轮测试创建或改动。 */
    private static final FileTime SUITE_STARTED = FileTime.from(Instant.now());

    @Test
    @DisplayName("运行期数据源指向本轮临时库，默认业务库未被本轮测试打开")
    void usesInjectedTemporaryDatabase() throws IOException {
        String file = jdbcTemplate.queryForObject(
                "SELECT file FROM pragma_database_list WHERE name = 'main'", String.class);

        assertThat(file).isNotNull();
        assertThat(Path.of(file).toAbsolutePath().normalize()).isEqualTo(TEST_DATABASE);
        assertThat(TEST_DATABASE).exists();
        // 开发者本地启动应用后业务库会正常存在，因此只断言本轮测试没有创建或写入它。
        Path business = Paths.get("data", "panel.db");
        if (Files.exists(business)) {
            assertThat(Files.getLastModifiedTime(business)).isLessThanOrEqualTo(SUITE_STARTED);
        }
        assertThat(GuardedSqliteDataSource.connectionsOpened()).isPositive();
    }

    @Test
    @DisplayName("越界业务路径在建立任何连接之前让上下文启动失败")
    void refusesBusinessPathBeforeOpening() {
        Path outside = TEST_ROOT.getParent().resolve("outside-" + UUID.randomUUID() + ".db");

        assertThatThrownBy(() -> new SpringApplicationBuilder(AiPanelApplication.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run("--panel.db.path=" + outside,
                        "--panel.db.allowed-root=" + TEST_ROOT,
                        "--panel.db.initialize=false",
                        "--logging.level.root=OFF"))
                .hasRootCauseInstanceOf(DataSourceSecurityException.class);

        assertThat(Files.exists(outside)).isFalse();
    }
}