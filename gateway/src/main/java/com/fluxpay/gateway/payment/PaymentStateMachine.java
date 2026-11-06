package com.fluxpay.gateway.payment;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * The single guarded entry point for every status change. Nothing else in the codebase
 * calls {@code Payment.setStatus}: callers ask the machine to {@link #transition}, which
 * (1) rejects any move the table below forbids and (2) appends one audit row on success.
 *
 * <p>Terminal states (SUCCEEDED, FAILED, EXPIRED, CANCELLED) map to an empty set, so a
 * late or duplicate event can never overwrite a finished payment. That single rule is
 * what the phase-2 "overwrite bug" demo exists to motivate.
 */
@Component
public class PaymentStateMachine {

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = new EnumMap<>(PaymentStatus.class);

    static {
        ALLOWED.put(PaymentStatus.CREATED, Set.of(PaymentStatus.PENDING, PaymentStatus.FAILED));
        ALLOWED.put(PaymentStatus.PENDING,
                Set.of(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.EXPIRED, PaymentStatus.CANCELLED));
        ALLOWED.put(PaymentStatus.SUCCEEDED, Set.of());
        ALLOWED.put(PaymentStatus.FAILED, Set.of());
        ALLOWED.put(PaymentStatus.EXPIRED, Set.of());
        ALLOWED.put(PaymentStatus.CANCELLED, Set.of());
    }

    private final PaymentTransitionRepository transitions;
    private final Clock clock;

    public PaymentStateMachine(PaymentTransitionRepository transitions, Clock clock) {
        this.transitions = transitions;
        this.clock = clock;
    }

    /**
     * Move {@code payment} to {@code to}, recording why and who. Must be called inside a
     * transaction: it mutates the managed entity and inserts the audit row together.
     *
     * @throws IllegalPaymentTransitionException if the move is not in the table
     */
    public void transition(Payment payment, PaymentStatus to, String reasonCode, TransitionSource source) {
        PaymentStatus from = payment.getStatus();
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalPaymentTransitionException(from, to);
        }
        var now = clock.instant();
        payment.setStatus(to);
        payment.touch(now);
        transitions.save(new PaymentTransition(UUID.randomUUID(), payment.getId(), from, to, reasonCode, source, now));
    }

    /** Exposed for the unit test that checks the table directly. */
    public static boolean isAllowed(PaymentStatus from, PaymentStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }
}
