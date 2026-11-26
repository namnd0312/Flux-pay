package com.fluxpay.gateway.risk;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads rule rows with plain SQL. The condition is selected as text ({@code condition::text})
 * and handed to {@link RuleCompiler} untouched, so there is one place that understands the
 * rule grammar.
 */
@Component
public class RiskRuleStore {

    private final JdbcClient jdbc;

    public RiskRuleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Enabled rules in evaluation order: lowest priority number first, then oldest. */
    public List<RuleRow> findEnabled() {
        return jdbc.sql("""
                        select id, merchant_id, priority, condition::text as condition, action, reason_code
                        from risk_rules where enabled order by priority, id
                        """)
                .query((rs, n) -> new RuleRow(
                        rs.getLong("id"),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getInt("priority"),
                        rs.getString("condition"),
                        rs.getString("action"),
                        rs.getString("reason_code")))
                .list();
    }

    /** A rule exactly as stored; nothing in it has been validated yet. */
    public record RuleRow(long id, UUID merchantId, int priority, String condition, String action,
            String reasonCode) {
    }
}
