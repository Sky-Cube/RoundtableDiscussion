package com.aipanel.config;

import java.nio.charset.StandardCharsets;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 启动时执行根目录 init.sql，建立版本化结构并写入六条幂等预设。
 *
 * <p>脚本可重复执行：已存在的预设不会被改写，重复初始化只保持行数与内容一致。
 */
public class DatabaseInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private static final int REQUIRED_PRESETS = 6;

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final PanelProperties properties;

    public DatabaseInitializer(DataSource dataSource, JdbcTemplate jdbcTemplate, PanelProperties properties) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.db().initialize()) {
            log.warn("已按配置跳过 SQLite 初始化：panel.db.initialize=false");
            return;
        }
        initialize();
    }

    /** 执行一次初始化脚本。测试可对独立临时库重复调用以验证幂等性。 */
    public void initialize() {
        Resource script = new DefaultResourceLoader().getResource(properties.db().initScript());
        if (!script.exists()) {
            throw new IllegalStateException("初始化脚本不存在：" + properties.db().initScript());
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.setContinueOnError(false);
        populator.setSeparator(";");
        populator.addScript(script);
        DatabasePopulatorUtils.execute(populator, dataSource);

        int presets = countPresets();
        if (presets < REQUIRED_PRESETS) {
            throw new IllegalStateException("初始化后预设数量不足：期望至少 " + REQUIRED_PRESETS + "，实际 " + presets);
        }
        log.info("SQLite 初始化完成：script={}，presets={}", properties.db().initScript(), presets);
    }

    public int countPresets() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM preset", Integer.class);
        return count == null ? 0 : count;
    }
}
