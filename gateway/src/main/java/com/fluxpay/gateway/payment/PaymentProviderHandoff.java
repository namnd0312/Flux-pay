package com.fluxpay.gateway.payment;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fluxpay.gateway.provider.PaymentProvider;
import com.fluxpay.gateway.provider.ProviderException;
import com.fluxpay.gateway.provider.ProviderPaymentRequest;
import com.fluxpay.gateway.provider.ProviderPaymentResult;

/**
 * Takes a payment that is already committed as CREATED to the provider and on to PENDING.
 * Both ways into CREATED end here: a new payment the risk rules allowed, and a held payment
 * an operator approved.
 *
 * <p>The provider HTTP call is made with no transaction open, so a pooled DB connection is
 * never held for the length of an HTTP timeout. That leaves two separate transactions around
 * the call, and the failure handling that goes with them:
 * <ul>
 *   <li>the provider refuses or cannot be reached: the CREATED row is compensated to FAILED
 *       (502);</li>
 *   <li>anything else goes wrong: compensated to FAILED with an internal reason (500);</li>
 *   <li>the JVM dies after the provider answered: the row stays CREATED and
 *       {@link PaymentSweepService#failAbandonedCreated} fails it later.</li>
 * </ul>
 */
@Component
class PaymentProviderHandoff {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderHandoff.class);

    private final PaymentRepository payments;
    private final PaymentStateMachine stateMachine;
    private final OptimisticRetry retry;
    private final TransactionTemplate tx;

    PaymentProviderHandoff(PaymentRepository payments, PaymentStateMachine stateMachine, OptimisticRetry retry,
            PlatformTransactionManager txManager) {
        this.payments = payments;
        this.stateMachine = stateMachine;
        this.retry = retry;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Must be called with no transaction open and with the payment committed as CREATED. */
    Payment submit(PaymentProvider provider, UUID id, UUID merchantId, long amount) {
        ProviderPaymentResult result;
        try {
            result = provider.createPayment(new ProviderPaymentRequest(id, amount, null, null));
        } catch (ProviderException ex) {
            compensateToFailed(id, merchantId, ex.getReasonCode());
            throw ex; // -> 502 PROVIDER_UNAVAILABLE
        } catch (RuntimeException ex) {
            log.error("Unexpected error creating payment {} at provider {}", id, provider.code(), ex);
            compensateToFailed(id, merchantId, "INTERNAL_ERROR");
            throw ex; // -> 500
        }

        // Record the provider result and move to PENDING. Retried if it loses a version race.
        return retry.execute("create", tx, () -> {
            var p = payments.findByIdAndMerchantId(id, merchantId).orElseThrow(() -> new PaymentNotFoundException(id));
            p.setProviderCode(provider.code());
            p.setProviderRef(result.providerRef());
            p.setPayUrl(result.payUrl());
            stateMachine.transition(p, PaymentStatus.PENDING, "PROVIDER_ACCEPTED", TransitionSource.SYSTEM);
            return p;
        });
    }

    private void compensateToFailed(UUID id, UUID merchantId, String reasonCode) {
        retry.execute("compensate", tx, () -> {
            var p = payments.findByIdAndMerchantId(id, merchantId)
                    .orElseThrow(() -> new PaymentNotFoundException(id));
            stateMachine.transition(p, PaymentStatus.FAILED, reasonCode, TransitionSource.SYSTEM);
            return p;
        });
    }
}
