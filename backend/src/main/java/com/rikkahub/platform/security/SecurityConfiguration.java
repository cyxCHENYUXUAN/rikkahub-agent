package com.rikkahub.platform.security;

import java.io.IOException;
import com.rikkahub.platform.storage.StoragePaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean LocalTokenService localTokenService(StoragePaths paths) throws IOException { return new LocalTokenService(paths); }

    @Bean FilterRegistrationBean<OriginGuard> originGuard(LocalTokenService tokens,
            @Value("${server.port:8081}") int port,
            @Value("${rikkahub.security.development-cors:false}") boolean developmentCors) {
        FilterRegistrationBean<OriginGuard> registration = new FilterRegistrationBean<>(new OriginGuard(tokens, port, developmentCors));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setAsyncSupported(true);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
