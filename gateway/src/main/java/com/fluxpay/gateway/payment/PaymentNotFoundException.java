package com.fluxpay.gateway.payment;

import java.util.UUID;

/**
 * Thrown when a payment id does not exist OR belongs to another merchant. The two cases
 * are deliberately indistinguishable (both 404) so a merchant cannot probe for the
 * existence of another merchant's payments by id.
 */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID id) {
        super("Payment not found: " + id);
    }
}
