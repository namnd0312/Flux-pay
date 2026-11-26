package com.fluxpay.gateway.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AdminTokenFilter} for {@code /internal/*} only, the same way the merchant
 * filter is registered for {@code /api/*}: built here rather than as a {@code @Component}, so
 * it is not also auto-registered against every URL. The two filters never see the same request.
 */
@Configuration
public class AdminTokenFilterConfig {

    @Bean
    public FilterRegistrationBean<AdminTokenFilter> adminTokenFilter(
            @Value("${fluxpay.admin.token:}") String token, ProblemDetailWriter problems) {
        var registration = new FilterRegistrationBean<>(new AdminTokenFilter(token, problems));
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(10);
        registration.setName("adminTokenFilter");
        return registration;
    }
}
