package com.fluxpay.gateway.payment.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One payment waiting for an operator. {@code holdReason} is the reason code of the rule that
 * held it, read from the transition ledger; {@code reviewDeadline} is when the sweeper will
 * expire it if nobody decides.
 */
public record ReviewItemResponse(UUID paymentId, UUID merchantId, String orderId, long amount, String provider,
        String holdReason, Instant heldAt, Instant reviewDeadline) {
}
