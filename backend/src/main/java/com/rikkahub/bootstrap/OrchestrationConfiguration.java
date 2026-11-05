package com.rikkahub.bootstrap;

import com.rikkahub.agent.orchestration.*;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class OrchestrationConfiguration {
    @Bean Engine orchestrationEngine(EngineStore store,Clock clock,IdGenerator ids,
            @Value("${rikkahub.orchestration.worker-slots:2}") int slots) {
        return new Engine(store,clock,ids,slots);
    }
}
