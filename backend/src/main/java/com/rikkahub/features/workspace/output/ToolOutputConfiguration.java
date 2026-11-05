package com.rikkahub.features.workspace.output;

import java.io.IOException;
import javax.sql.DataSource;
import com.rikkahub.platform.storage.StoragePaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One durable output index per application; quotas are configuration, not per session. */
@Configuration(proxyBeanMethods = false)
public class ToolOutputConfiguration {
    @Bean(destroyMethod = "close")
    @DependsOnDatabaseInitialization
    ToolOutputStore toolOutputStore(StoragePaths paths, DataSource db,
            @Value("${rikkahub.outputs.per-output-bytes:524288}") int perOutputBytes,
            @Value("${rikkahub.outputs.per-session-bytes:8388608}") long perSessionBytes,
            @Value("${rikkahub.outputs.total-bytes:67108864}") long totalBytes,
            @Value("${rikkahub.outputs.per-session-count:128}") int perSessionCount,
            @Value("${rikkahub.outputs.total-count:1024}") int totalCount) throws IOException {
        ToolOutputStore store = new ToolOutputStore(paths, db,
                new ToolOutputStore.Limits(perOutputBytes, perSessionBytes, totalBytes, perSessionCount, totalCount));
        store.recoverPending();
        return store;
    }
}
