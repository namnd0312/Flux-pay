package com.fluxpay.mockprovider.pay;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fluxpay.mockprovider.session.PaymentSessionStore;
import com.fluxpay.mockprovider.webhook.WebhookSender;

/**
 * Simulates the customer paying at the provider's hosted page. Marks the session with the
 * outcome, kicks off the (async) signed webhook back to the gateway, and answers 202 at
 * once -- the real result reaches the gateway out of band via the webhook, exactly like a
 * production provider.
 */
@RestController
@RequestMapping("/mock/v1/pay")
public class PayController {

    private final PaymentSessionStore store;
    private final WebhookSender sender;

    public PayController(PaymentSessionStore store, WebhookSender sender) {
        this.store = store;
        this.sender = sender;
    }

    @PostMapping("/{providerRef}")
    public ResponseEntity<PayResponse> pay(@PathVariable String providerRef,
            @RequestParam(defaultValue = "SUCCESS") String outcome) {
        return store.get(providerRef).map(session -> {
            store.put(session.withStatus(outcome));
            sender.send(providerRef, session.merchantRef(), session.amount(), outcome);
            return ResponseEntity.accepted().body(new PayResponse(providerRef, outcome, true));
        }).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record PayResponse(String providerRef, String outcome, boolean accepted) {
    }
}
