package com.fluxpay.gateway.risk;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * What a rule is allowed to look at for one payment-create request. This record is the whole
 * vocabulary of the rule language: a condition can only name a field listed in
 * {@link #FIELD_TYPES}, so a rule row can never reach into arbitrary objects or code.
 *
 * <p>Every fact is known before anything is written, and none of them needs a query
 * ("how many payments in the last minute" is deliberately not a fact).
 */
public record RiskFacts(long amount, String provider, UUID merchantId, String orderId, int hourOfDay) {

    /** The value type of a fact; it decides which operators and literal values a rule may use. */
    public enum FieldType { NUMBER, STRING }

    /** The whitelist: field name in a rule's JSON -> its type. */
    public static final Map<String, FieldType> FIELD_TYPES = Map.of(
            "amount", FieldType.NUMBER,
            "hourOfDay", FieldType.NUMBER,
            "provider", FieldType.STRING,
            "merchantId", FieldType.STRING,
            "orderId", FieldType.STRING);

    /** Builds the facts for a request seen at {@code now}; the hour is taken in {@code zone}. */
    public static RiskFacts of(long amount, String provider, UUID merchantId, String orderId, Instant now,
            ZoneId zone) {
        return new RiskFacts(amount, provider, merchantId, orderId, now.atZone(zone).getHour());
    }

    /** The facts keyed by rule field name. Numbers are {@code Long}, everything else {@code String}. */
    public Map<String, Object> asMap() {
        return Map.of(
                "amount", amount,
                "hourOfDay", (long) hourOfDay,
                "provider", provider,
                "merchantId", merchantId.toString(),
                "orderId", orderId);
    }
}
