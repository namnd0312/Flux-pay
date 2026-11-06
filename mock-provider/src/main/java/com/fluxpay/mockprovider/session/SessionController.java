package com.fluxpay.mockprovider.session;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fake provider's session API, standing in for a real acquirer's "create payment"
 * call. Returns a provider reference and a pay URL; the customer-pays endpoint that turns
 * a session into a webhook is added in phase 4.
 */
@RestController
@RequestMapping("/mock/v1/payments")
public class SessionController {

    private final PaymentSessionStore store;
    private final String publicBaseUrl;

    public SessionController(PaymentSessionStore store,
            @Value("${mock.public-base-url:http://localhost:8081}") String publicBaseUrl) {
        this.store = store;
        this.publicBaseUrl = publicBaseUrl;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSessionResponse create(@RequestBody CreateSessionRequest request) {
        String providerRef = "mp_" + UUID.randomUUID();
        store.put(new PaymentSession(providerRef, request.merchantRef(), request.amount(),
                request.description(), request.returnUrl(), "NEW"));
        String payUrl = publicBaseUrl + "/mock/v1/pay/" + providerRef;
        return new CreateSessionResponse(providerRef, payUrl);
    }

    @GetMapping("/{providerRef}")
    public ResponseEntity<PaymentSession> get(@PathVariable String providerRef) {
        return store.get(providerRef).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateSessionRequest(String merchantRef, long amount, String description, String returnUrl) {
    }

    public record CreateSessionResponse(String providerRef, String payUrl) {
    }
}
