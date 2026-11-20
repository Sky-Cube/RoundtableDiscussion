package com.aipanel.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 数据源、路径守卫与初始化装配。 */
@Configuration
@EnableConfigurationProperties(PanelProperties.class)
public class PanelDataSourceConfiguration {

    @Bean
    public SqliteDatabase sqliteDatabase(PanelProperties properties) {
        Path allowedRoot = Paths.get(properties.db().allowedRoot()).toAbsolutePath().normalize();
        Path database = SqlitePathGuard.resolve(properties.db().path(), properties.db().allowedRoot());
        return new SqliteDatabase(database, allowedRoot);
    }

    @Bean
    public DataSource dataSource(SqliteDatabase database) {
        return new GuardedSqliteDataSource(database.path(), database.allowedRoot());
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public DatabaseInitializer databaseInitializer(DataSource dataSource, JdbcTemplate jdbcTemplate,
            PanelProperties properties) {
        return new DatabaseInitializer(dataSource, jdbcTemplate, properties);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
