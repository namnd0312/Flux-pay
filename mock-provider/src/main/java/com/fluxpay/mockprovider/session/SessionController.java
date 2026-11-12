package com.fluxpay.mockprovider.session;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fluxpay.mockprovider.config.MockProperties;
import com.fluxpay.mockprovider.webhook.WebhookSender;

/**
 * The fake provider's session API, standing in for a real acquirer's "create payment"
 * call. Returns a provider reference and a pay URL.
 *
 * <p>Two knobs reproduce timing a real provider can show:
 * <ul>
 *   <li>{@code mock.callback.auto-pay}: the session is paid the instant it is created and
 *       the webhook is sent right away, so it can reach the gateway <em>before</em> the
 *       gateway has processed this very response (the early-webhook race);</li>
 *   <li>{@code mock.create.delay-ms}: the response is held back (a slow provider), which
 *       also widens that race.</li>
 * </ul>
 */
@RestController
@RequestMapping("/mock/v1/payments")
public class SessionController {

    private final PaymentSessionStore store;
    private final WebhookSender sender;
    private final MockProperties properties;

    public SessionController(PaymentSessionStore store, WebhookSender sender, MockProperties properties) {
        this.store = store;
        this.sender = sender;
        this.properties = properties;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionResponse create(@RequestBody CreateSessionRequest request) {
        String providerRef = "mp_" + UUID.randomUUID();
        var session = new PaymentSession(providerRef, request.merchantRef(), request.amount(),
                request.description(), request.returnUrl(), "NEW");
        String autoPay = properties.callback().autoPay();
        if ("NONE".equalsIgnoreCase(autoPay)) {
            store.put(session);
        } else {
            store.put(session.withStatus(autoPay));
            sender.send(providerRef, session.merchantRef(), session.amount(), autoPay); // async
        }
        holdResponse(properties.create().delayMs());
        String payUrl = properties.publicBaseUrl() + "/mock/v1/pay/" + providerRef;
        return new CreateSessionResponse(providerRef, payUrl);
    }

    @GetMapping("/{providerRef}")
    public ResponseEntity<PaymentSession> get(@PathVariable String providerRef) {
        return store.get(providerRef).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static void holdResponse(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public record CreateSessionRequest(String merchantRef, long amount, String description, String returnUrl) {
    }

    public record CreateSessionResponse(String providerRef, String payUrl) {
    }
}
