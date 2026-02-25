package com.platform.payment.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.platform.payment.domain.Payment;
import com.platform.payment.domain.Refund;
import com.platform.payment.repository.PaymentRepository;
import com.platform.payment.repository.RefundRepository;
import com.platform.shared.events.EventTypes;
import com.platform.shared.events.Events.PaymentCompletedEvent;
import com.platform.shared.events.Events.PaymentFailedEvent;
import com.platform.shared.events.Events.PaymentInitiatedEvent;
import com.platform.shared.events.Events.PaymentRefundedEvent;
import com.platform.shared.kafka.EventPublisher;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Payment Service — Saga Participant
 *
 * Idempotent by design: if PAYMENT_INITIATED arrives twice for the same orderId,
 * the second call returns the stored result without charging again.
 *
 * Compensation: PAYMENT_REFUNDED triggers a refund (also idempotent).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final EventPublisher eventPublisher;

    @Transactional
    public void processPayment(PaymentInitiatedEvent event) {
        String orderId = event.getOrderId();

        // ── Idempotency: check if payment already exists ───────────────────────
        Optional<Payment> existing = paymentRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            log.info("Duplicate payment request — republishing cached result: orderId={}", orderId);
            republishResult(existing.get(), event);
            return;
        }

        String paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Payment.Status status = Payment.Status.COMPLETED;
        String failureReason = null;

        try {
            // Simulate payment gateway (5% failure rate for testing compensation)
            simulateGateway(paymentId, event.getAmount());
        } catch (PaymentDeclinedException ex) {
            status = Payment.Status.FAILED;
            failureReason = ex.getMessage();
        }

        Payment payment = Payment.builder()
                .id(paymentId)
                .orderId(orderId)
                .customerId(event.getCustomerId())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .paymentMethod(event.getPaymentMethod())
                .status(status)
                .failureReason(failureReason)
                .build();

        paymentRepository.save(payment);

        if (status == Payment.Status.COMPLETED) {
            eventPublisher.publish(EventTypes.TOPIC_PAYMENTS_COMPLETED,
                    new PaymentCompletedEvent(orderId, paymentId, event.getAmount(),
                            event.getCurrency(), event.getCorrelationId(), event.getId()));
            log.info("Payment completed: orderId={}, paymentId={}, amount={}", orderId, paymentId, event.getAmount());
        } else {
            eventPublisher.publish(EventTypes.TOPIC_PAYMENTS_FAILED,
                    new PaymentFailedEvent(orderId, failureReason, event.getCorrelationId(), event.getId()));
            log.warn("Payment failed: orderId={}, reason={}", orderId, failureReason);
        }
    }

    @Transactional
    public void processRefund(PaymentRefundedEvent event) {
        paymentRepository.findByOrderId(event.getOrderId()).ifPresent(payment -> {
            if (refundRepository.findByPaymentId(payment.getId()).isPresent()) {
                log.info("Duplicate refund request — skipping: paymentId={}", payment.getId());
                return;
            }

            String refundId = "ref_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            refundRepository.save(Refund.builder()
                    .id(refundId).paymentId(payment.getId())
                    .orderId(event.getOrderId()).amount(event.getAmount())
                    .currency(event.getCurrency()).status("COMPLETED").build());

            payment.setStatus(Payment.Status.REFUNDED);
            paymentRepository.save(payment);

            log.info("Refund processed: refundId={}, paymentId={}, orderId={}", refundId, payment.getId(), event.getOrderId());
        });
    }

    private void republishResult(Payment payment, PaymentInitiatedEvent trigger) {
        if (payment.getStatus() == Payment.Status.COMPLETED) {
            eventPublisher.publish(EventTypes.TOPIC_PAYMENTS_COMPLETED,
                    new PaymentCompletedEvent(payment.getOrderId(), payment.getId(),
                            payment.getAmount(), payment.getCurrency(),
                            trigger.getCorrelationId(), trigger.getId()));
        } else {
            eventPublisher.publish(EventTypes.TOPIC_PAYMENTS_FAILED,
                    new PaymentFailedEvent(payment.getOrderId(), payment.getFailureReason(),
                            trigger.getCorrelationId(), trigger.getId()));
        }
    }

    private void simulateGateway(String paymentId, BigDecimal amount) {
        try { Thread.sleep((long)(Math.random() * 200 + 50)); } catch (InterruptedException ignored) {}
        if (Math.random() < 0.05) {
            throw new PaymentDeclinedException("Payment gateway declined: insufficient funds");
        }
    }

    static class PaymentDeclinedException extends RuntimeException {
        PaymentDeclinedException(String msg) { super(msg); }
    }
}