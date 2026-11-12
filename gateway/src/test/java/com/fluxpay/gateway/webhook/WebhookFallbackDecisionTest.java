package com.fluxpay.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.fluxpay.gateway.payment.OptimisticRetry;
import com.fluxpay.gateway.payment.Payment;
import com.fluxpay.gateway.payment.PaymentRepository;
import com.fluxpay.gateway.payment.PaymentStateMachine;
import com.fluxpay.gateway.payment.PaymentStatus;
import com.fluxpay.gateway.payment.PaymentTransitionRepository;
import com.fluxpay.gateway.payment.TransitionSource;
import com.fluxpay.gateway.provider.ProviderWebhookEvent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The decision taken when an event's provider reference matches no payment, for each state
 * the payment found by {@code merchantRef} can be in. Tested as a pure function because one
 * of the rows (create committed between the two lookups) cannot be forced end to end.
 */
class WebhookFallbackDecisionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String REF = "mp_1";

    private final PaymentStateMachine machine =
            new PaymentStateMachine(mock(PaymentTransitionRepository.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void noPaymentIsRejectedForGoodWithNothingToLink() {
        WebhookOutcome o = WebhookPaymentApplier.decideWithoutProviderRef(null, "MOCK", event(REF));

        assertThat(o.rejectReason()).isEqualTo("UNKNOWN_PAYMENT");
        assertThat(o.paymentId()).isNull();
    }

    @Test
    void createdPaymentMeansComeBackLater() {
        Payment created = newPayment();

        WebhookOutcome o = WebhookPaymentApplier.decideWithoutProviderRef(created, "MOCK", event(REF));

        assertThat(o.isRetryLater()).isTrue();
        assertThat(o.retryReason()).isEqualTo("PAYMENT_NOT_READY");
        assertThat(o.paymentId()).isEqualTo(created.getId());
    }

    @Test
    void pendingPaymentWithThisReferenceContinuesOnTheNormalPath() {
        // The create flow committed between the lookup by reference and the read by id.
        Payment pending = newPayment();
        pending.setProviderCode("MOCK");
        pending.setProviderRef(REF);
        machine.transition(pending, PaymentStatus.PENDING, "PROVIDER_ACCEPTED", TransitionSource.SYSTEM);

        assertThat(WebhookPaymentApplier.decideWithoutProviderRef(pending, "MOCK", event(REF))).isNull();
    }

    @Test
    void failedPaymentThatNeverGotAReferenceIsRejectedButLinked() {
        Payment failed = newPayment();
        machine.transition(failed, PaymentStatus.FAILED, "PROVIDER_UNAVAILABLE", TransitionSource.SYSTEM);

        WebhookOutcome o = WebhookPaymentApplier.decideWithoutProviderRef(failed, "MOCK", event(REF));

        assertThat(o.rejectReason()).isEqualTo("PAYMENT_NEVER_ACCEPTED");
        assertThat(o.paymentId()).isEqualTo(failed.getId());
    }

    @Test
    void paymentBoundToAnotherReferenceIsAMismatch() {
        Payment pending = newPayment();
        pending.setProviderCode("MOCK");
        pending.setProviderRef("mp_other");
        machine.transition(pending, PaymentStatus.PENDING, "PROVIDER_ACCEPTED", TransitionSource.SYSTEM);

        WebhookOutcome o = WebhookPaymentApplier.decideWithoutProviderRef(pending, "MOCK", event(REF));

        assertThat(o.rejectReason()).isEqualTo("REF_MISMATCH");
        assertThat(o.paymentId()).isEqualTo(pending.getId());
    }

    @Test
    void applyContinuesWithThePaymentFoundByIdAndAppliesTheEvent() {
        // Lookup by provider reference misses (it ran before the create flow committed); the
        // read by id then returns the payment already PENDING with this very reference.
        Payment pending = newPayment();
        pending.setProviderCode("MOCK");
        pending.setProviderRef(REF);
        machine.transition(pending, PaymentStatus.PENDING, "PROVIDER_ACCEPTED", TransitionSource.SYSTEM);
        PaymentRepository payments = mock(PaymentRepository.class);
        when(payments.findByProviderCodeAndProviderRef("MOCK", REF)).thenReturn(Optional.empty());
        when(payments.loadForSweep(pending.getId())).thenReturn(Optional.of(pending));
        var applier = new WebhookPaymentApplier(payments, machine, new OptimisticRetry(new SimpleMeterRegistry()),
                mock(PlatformTransactionManager.class), "optimistic", Duration.ofSeconds(2));

        WebhookOutcome o = applier.apply("MOCK", new ProviderWebhookEvent("evt_1", REF,
                pending.getId().toString(), "SUCCESS", 1000L, NOW.toString()));

        assertThat(o.isApplied()).isTrue();
        assertThat(o.paymentId()).isEqualTo(pending.getId());
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void signedEventWithoutAReferenceOrWithAnUnknownStatusIsRejectedWithoutTouchingAnyPayment() {
        PaymentRepository payments = mock(PaymentRepository.class);
        var applier = new WebhookPaymentApplier(payments, machine, new OptimisticRetry(new SimpleMeterRegistry()),
                mock(PlatformTransactionManager.class), "optimistic", Duration.ofSeconds(2));
        String id = UUID.randomUUID().toString();

        assertThat(applier.apply("MOCK", new ProviderWebhookEvent("evt_1", null, id, "SUCCESS", 1000L, null))
                .rejectReason()).isEqualTo("MALFORMED_EVENT");
        assertThat(applier.apply("MOCK", new ProviderWebhookEvent("evt_2", REF, id, "success", 1000L, null))
                .rejectReason()).isEqualTo("MALFORMED_EVENT");
        verifyNoInteractions(payments);
    }

    private static Payment newPayment() {
        return new Payment(UUID.randomUUID(), UUID.randomUUID(), "ORD-1", 1000L, NOW.plusSeconds(900), NOW);
    }

    private static ProviderWebhookEvent event(String providerRef) {
        return new ProviderWebhookEvent("evt_1", providerRef, UUID.randomUUID().toString(), "SUCCESS", 1000L,
                NOW.toString());
    }
}
