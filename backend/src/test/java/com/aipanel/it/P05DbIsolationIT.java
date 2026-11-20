package com.aipanel.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    @Test
    @DisplayName("运行期数据源指向本轮临时库，默认业务库不存在")
    void usesInjectedTemporaryDatabase() {
        String file = jdbcTemplate.queryForObject(
                "SELECT file FROM pragma_database_list WHERE name = 'main'", String.class);

        assertThat(file).isNotNull();
        assertThat(Path.of(file).toAbsolutePath().normalize()).isEqualTo(TEST_DATABASE);
        assertThat(TEST_DATABASE).exists();
        assertThat(Paths.get("data", "panel.db")).doesNotExist();
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