package com.rikkahub.bootstrap;

import java.io.IOException;
import com.rikkahub.platform.recovery.InstanceLock;
import com.rikkahub.platform.recovery.RecoveryService;
import com.rikkahub.platform.storage.StoragePaths;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class LifecycleConfiguration {
    @Bean(destroyMethod = "close")
    InstanceLock instanceLock(StoragePaths paths) throws IOException { return InstanceLock.acquire(paths); }

    @Bean @DependsOnDatabaseInitialization
    SmartInitializingSingleton recoverBeforeServingRequests(RecoveryService recovery,
            com.rikkahub.features.orchestration.application.Watchdog watchdog) {
        return () -> { recovery.recover(); watchdog.recover(); };
    }
}
