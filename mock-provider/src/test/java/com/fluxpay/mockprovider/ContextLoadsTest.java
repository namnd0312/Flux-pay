package com.fluxpay.mockprovider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

/**
 * The mock-provider context boots and the Boot 4 {@code RestClient.Builder}
 * bean (used to call the gateway webhook from phase 4) is resolvable.
 */
@SpringBootTest
class ContextLoadsTest {

    @Autowired
    RestClient.Builder restClientBuilder;

    @Test
    void restClientBuilderIsResolvable() {
        assertThat(restClientBuilder).isNotNull();
    }
}
