package com.fluxpay.gateway.risk;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Writes one {@code risk_decisions} row per evaluated request. The facts are stored exactly as
 * the rules saw them ({@link RiskFacts#asMap()}), so a decision can be explained later even if
 * the rule has changed.
 *
 * <p>{@link #record} runs in the caller's transaction when there is one. That is how an
 * allowed decision is tied to its payment: both rows commit or neither does.
 */
@Component
public class RiskDecisionStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public RiskDecisionStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** {@code paymentId} is null for a rejected request, which has no payment. */
    public void record(RiskDecision decision, RiskFacts facts, UUID paymentId, Instant now) {
        jdbc.sql("""
                        insert into risk_decisions
                            (merchant_id, order_id, payment_id, decision, matched_rule_id, reason_code, facts, created_at)
                        values (:merchant, :order, :payment, :decision, :rule, :reason, cast(:facts as jsonb), :now)
                        """)
                .param("merchant", facts.merchantId())
                .param("order", facts.orderId())
                .param("payment", paymentId, Types.OTHER)
                .param("decision", decision.outcome().name())
                .param("rule", decision.ruleId(), Types.BIGINT)
                .param("reason", decision.reasonCode(), Types.VARCHAR)
                .param("facts", objectMapper.writeValueAsString(facts.asMap()))
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }
}
