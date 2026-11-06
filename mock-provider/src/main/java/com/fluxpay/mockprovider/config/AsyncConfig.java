package com.fluxpay.mockprovider.config;

import java.util.concurrent.Executor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables async webhook sending so the pay endpoint can return 202 immediately while the
 * webhook is delivered on a dedicated executor (mirroring a real provider's out-of-band
 * IPN). Also binds {@link MockProperties}.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(MockProperties.class)
public class AsyncConfig {

    @Bean("webhookExecutor")
    public Executor webhookExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("webhook-");
        executor.initialize();
        return executor;
    }
}
