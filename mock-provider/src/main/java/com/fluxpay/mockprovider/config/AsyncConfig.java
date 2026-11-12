package com.fluxpay.mockprovider.config;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Enables async webhook sending so the pay endpoint can return 202 immediately while the
 * webhook is delivered on a dedicated executor (mirroring a real provider's out-of-band
 * IPN). Also binds {@link MockProperties}.
 *
 * <p>The pool is sized from config because a delivery sleeps on its thread (delay, retry
 * backoff): under a load test a small pool would make the <em>mock</em> the slowest part of
 * the system. If the queue still overflows, the webhook is dropped, but never silently:
 * it is logged and counted in {@code mock.webhook.rejected}, so a load run can prove the
 * test double was not the bottleneck.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(MockProperties.class)
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean("webhookExecutor")
    public Executor webhookExecutor(MockProperties properties, MeterRegistry meters) {
        var executor = new ThreadPoolTaskExecutor();
        // Core = max: a ThreadPoolExecutor only grows past core when its queue is full, so a
        // separate "max" would never be used with a large queue.
        executor.setCorePoolSize(properties.executor().threads());
        executor.setMaxPoolSize(properties.executor().threads());
        executor.setQueueCapacity(properties.executor().queue());
        executor.setThreadNamePrefix("webhook-");
        executor.setRejectedExecutionHandler((task, pool) -> {
            meters.counter("mock.webhook.rejected").increment();
            log.warn("Webhook dropped: sender pool and queue are full (threads={}, queue={})",
                    properties.executor().threads(), properties.executor().queue());
        });
        executor.initialize();
        return executor;
    }
}
