package com.fluxpay.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.fluxpay.gateway.provider.PaymentProvider;
import com.fluxpay.gateway.provider.ProviderRegistry;
import com.fluxpay.gateway.provider.ProviderWebhookEvent;

/**
 * The one path the integration tests cannot provoke on demand: applying the event blows up
 * with something unexpected. The event must be handed back for redelivery (RETRYABLE), not
 * left RECEIVED, and the original error must still reach the caller (500).
 */
class WebhookIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void unexpectedFailureReleasesTheEventForRedeliveryAndRethrows() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        var event = new ProviderWebhookEvent("evt_1", "mp_1", UUID.randomUUID().toString(), "SUCCESS", 1000L, null);
        UUID rowId = UUID.randomUUID();

        PaymentProvider provider = mock(PaymentProvider.class);
        when(provider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(provider.parseWebhook(body)).thenReturn(event);
        ProviderRegistry providers = mock(ProviderRegistry.class);
        when(providers.get("MOCK")).thenReturn(provider);
        WebhookEventStore events = mock(WebhookEventStore.class);
        when(events.insertNew(eq("MOCK"), eq("evt_1"), any(), any())).thenReturn(Optional.of(rowId));
        WebhookPaymentApplier applier = mock(WebhookPaymentApplier.class);
        when(applier.apply("MOCK", event)).thenThrow(new IllegalStateException("boom"));

        var service = new WebhookIngestionService(providers, applier, events,
                mock(PlatformTransactionManager.class), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.process("MOCK", body, Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");

        verify(events).markRetryable(eq(rowId), isNull(), eq("INTERNAL_ERROR"), any());
        verify(events, never()).markApplied(any(), any(), any());
        verify(events, never()).markRejected(any(), any(), any(), any());
    }
}
