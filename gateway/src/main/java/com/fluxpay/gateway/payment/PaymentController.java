package com.fluxpay.gateway.payment;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fluxpay.gateway.merchant.MerchantContext;
import com.fluxpay.gateway.payment.dto.CreatePaymentRequest;
import com.fluxpay.gateway.payment.dto.CreatePaymentResponse;
import com.fluxpay.gateway.payment.dto.PaymentDetailResponse;

import jakarta.validation.Valid;

/**
 * Merchant-facing payment API. The merchant is no longer read from a raw header here: the
 * {@code MerchantAuthFilter} (phase 5) verifies the HMAC signature and places the
 * authenticated {@link MerchantContext} on the request, so the controller trusts
 * {@code ctx.merchantId()} without any further check. Every read and write is scoped to
 * that merchant id, so one merchant can never see or act on another's payments.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentOrchestrator orchestrator;

    public PaymentController(PaymentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatePaymentResponse create(@RequestAttribute(name = MerchantContext.ATTRIBUTE) MerchantContext ctx,
            @Valid @RequestBody CreatePaymentRequest req) {
        Payment payment = orchestrator.create(ctx.merchantId(), req.orderId(), req.amount(), req.provider());
        return CreatePaymentResponse.from(payment);
    }

    @GetMapping("/{id}")
    public PaymentDetailResponse get(@RequestAttribute(name = MerchantContext.ATTRIBUTE) MerchantContext ctx,
            @PathVariable UUID id) {
        Payment payment = orchestrator.get(id, ctx.merchantId());
        return PaymentDetailResponse.from(payment, orchestrator.transitionsOf(payment.getId()));
    }

    @PostMapping("/{id}/cancel")
    public PaymentDetailResponse cancel(@RequestAttribute(name = MerchantContext.ATTRIBUTE) MerchantContext ctx,
            @PathVariable UUID id) {
        Payment payment = orchestrator.cancel(id, ctx.merchantId());
        return PaymentDetailResponse.from(payment, orchestrator.transitionsOf(payment.getId()));
    }
}
