package com.fluxpay.gateway.payment;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fluxpay.gateway.merchant.MerchantResolver;
import com.fluxpay.gateway.payment.dto.CreatePaymentRequest;
import com.fluxpay.gateway.payment.dto.CreatePaymentResponse;
import com.fluxpay.gateway.payment.dto.PaymentDetailResponse;

import jakarta.validation.Valid;

/**
 * Merchant-facing payment API. The merchant is identified by the {@code X-Merchant-Id}
 * header (an API key) resolved to an internal UUID; every read is scoped to that merchant
 * so one merchant can never see another's payments. Phase 5 moves identity into an
 * HMAC-verifying filter and this controller stops reading the header directly.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final MerchantResolver merchants;
    private final PaymentOrchestrator orchestrator;

    public PaymentController(MerchantResolver merchants, PaymentOrchestrator orchestrator) {
        this.merchants = merchants;
        this.orchestrator = orchestrator;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatePaymentResponse create(@RequestHeader(name = "X-Merchant-Id", required = false) String apiKey,
            @Valid @RequestBody CreatePaymentRequest req) {
        UUID merchantId = merchants.resolve(apiKey);
        Payment payment = orchestrator.create(merchantId, req.orderId(), req.amount());
        return CreatePaymentResponse.from(payment);
    }

    @GetMapping("/{id}")
    public PaymentDetailResponse get(@RequestHeader(name = "X-Merchant-Id", required = false) String apiKey, @PathVariable UUID id) {
        UUID merchantId = merchants.resolve(apiKey);
        Payment payment = orchestrator.get(id, merchantId);
        return PaymentDetailResponse.from(payment, orchestrator.transitionsOf(payment.getId()));
    }

    @PostMapping("/{id}/cancel")
    public PaymentDetailResponse cancel(@RequestHeader(name = "X-Merchant-Id", required = false) String apiKey, @PathVariable UUID id) {
        UUID merchantId = merchants.resolve(apiKey);
        Payment payment = orchestrator.cancel(id, merchantId);
        return PaymentDetailResponse.from(payment, orchestrator.transitionsOf(payment.getId()));
    }
}
