package com.rikkahub.platform.storage;

import java.io.IOException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class DatabaseConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseConfiguration.class);

    @Bean
    StoragePaths storagePaths(StorageProperties properties) throws IOException {
        return StoragePaths.initialize(properties.dataDirectory());
    }

    @Bean(destroyMethod = "close")
    @DependsOn("instanceLock")
    HikariDataSource dataSource(StoragePaths paths, StorageProperties properties) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(properties.busyTimeoutMs());

        SQLiteDataSource delegate = new SQLiteDataSource(sqlite);
        delegate.setUrl("jdbc:sqlite:" + paths.databaseFile());

        HikariConfig pool = new HikariConfig();
        pool.setDataSource(delegate);
        pool.setPoolName("rikkahub-sqlite");
        // Serialize database access; model/tool execution must not hold this
        // connection.
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(1);
        pool.setConnectionTimeout(properties.busyTimeoutMs() + 5000L);
        LOG.info("SQLite database: {}", paths.databaseFile());
        return new HikariDataSource(pool);
    }
}
