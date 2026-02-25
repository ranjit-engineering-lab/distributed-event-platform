package com.platform.payment.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.platform.payment.domain.Refund;

@Repository 
public interface RefundRepository extends JpaRepository<Refund, String> {
    Optional<Refund> findByPaymentId(String paymentId);
}