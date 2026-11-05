package com.rikkahub.bootstrap;

import java.time.Instant;
import java.util.UUID;
import com.rikkahub.agent.port.Clock;
import com.rikkahub.agent.port.IdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {
    @Bean @ConditionalOnMissingBean(Clock.class)
    Clock agentClock() {return Instant::now;}

    @Bean @ConditionalOnMissingBean(IdGenerator.class)
    IdGenerator agentIds() {return prefix -> prefix+"-"+UUID.randomUUID();}
}
