package com.fluxpay.gateway.idempotency;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fluxpay.gateway.common.ProblemDetailWriter;
import com.fluxpay.gateway.merchant.MerchantAuthFilterConfig;

/**
 * Registers {@link IdempotencyFilter} on the create path only, ordered after the auth
 * filter so a {@link com.fluxpay.gateway.merchant.MerchantContext} is always present. The
 * url pattern {@code /api/v1/payments} matches exactly the create endpoint, not
 * {@code /api/v1/payments/{id}} or {@code .../cancel}, so reads and cancels are never
 * idempotency-gated; the filter also re-checks the method is POST.
 */
@Configuration
public class IdempotencyFilterConfig {

    /** After auth; leaves a gap for any future filter between the two. */
    public static final int ORDER = MerchantAuthFilterConfig.ORDER + 10;

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
            IdempotencyService idempotency, ProblemDetailWriter problems) {
        var registration = new FilterRegistrationBean<>(new IdempotencyFilter(idempotency, problems));
        registration.addUrlPatterns("/api/v1/payments");
        registration.setOrder(ORDER);
        registration.setName("idempotencyFilter");
        return registration;
    }
}
