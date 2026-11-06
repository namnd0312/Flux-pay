package com.fluxpay.gateway.payment;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransitionRepository extends JpaRepository<PaymentTransition, UUID> {

    List<PaymentTransition> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
