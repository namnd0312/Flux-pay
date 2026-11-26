package com.fluxpay.gateway.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import com.fluxpay.gateway.AbstractIntegrationTest;

/**
 * With the scheduler on, a rule inserted by SQL becomes active by itself: nobody calls
 * {@code refresh()} and nothing restarts. The other IT classes run with scheduling off and
 * reload by hand, so this is the one place that proves the timer is wired.
 *
 * <p>The properties below give this class its own application context with a short reload
 * interval. The payment sweeper shares the scheduler switch, so its interval is pushed out.
 */
@TestPropertySource(properties = {"fluxpay.scheduling.enabled=true", "fluxpay.risk.refresh-interval=200ms",
        "fluxpay.scheduling.sweep-interval=1h"})
class RiskRuleScheduledRefreshIT extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RiskRuleCache cache;

    @AfterEach
    void removeTestRules() {
        jdbc.sql("delete from risk_rules where name like 'it-%'").update();
    }

    private List<String> activeReasons() {
        return cache.current().stream().map(CompiledRule::reasonCode).toList();
    }

    @Test
    void aRuleInsertedBySqlBecomesActiveWithinARefreshInterval() throws Exception {
        assertThat(activeReasons()).doesNotContain("SCHEDULED_REFRESH_PROBE");

        jdbc.sql("""
                insert into risk_rules (name, priority, condition, action, reason_code)
                values ('it-scheduled-refresh', 5, '{"field":"amount","op":"gt","value":999999999999}',
                        'REJECT', 'SCHEDULED_REFRESH_PROBE')
                """).update();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!activeReasons().contains("SCHEDULED_REFRESH_PROBE") && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(activeReasons()).contains("SCHEDULED_REFRESH_PROBE");
    }
}
