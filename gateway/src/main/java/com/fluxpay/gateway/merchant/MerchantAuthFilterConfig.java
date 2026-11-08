package com.fluxpay.gateway.merchant;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fluxpay.gateway.common.HmacSigner;
import com.fluxpay.gateway.common.ProblemDetailWriter;

/**
 * Registers {@link MerchantAuthFilter} for {@code /api/*} only. The filter is built here
 * (not annotated {@code @Component}) so a {@link FilterRegistrationBean} controls both its
 * URL mapping and its order; a {@code @Component} filter would otherwise also be
 * auto-registered against every URL, including {@code /webhooks/**}.
 *
 * <p>{@code ORDER} leaves room before the idempotency filter (phase 6), which must run
 * after authentication so it only ever stores responses for an authenticated merchant.
 */
@Configuration
public class MerchantAuthFilterConfig {

    /** Auth runs first among the gateway's servlet filters. */
    public static final int ORDER = 10;

    @Bean
    public FilterRegistrationBean<MerchantAuthFilter> merchantAuthFilter(
            MerchantRepository merchants, SecretCipher cipher, HmacSigner signer, ProblemDetailWriter problems,
            Clock clock, @Value("${fluxpay.auth.timestamp-tolerance}") Duration tolerance,
            @Value("${fluxpay.auth.max-body-bytes}") int maxBodyBytes) {
        var filter = new MerchantAuthFilter(merchants, cipher, signer, problems, clock, tolerance, maxBodyBytes);
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(ORDER);
        registration.setName("merchantAuthFilter");
        return registration;
    }
}
