package com.platform.order.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.platform.order.domain.OrderEventRecord;

@Repository
public interface OrderEventRecordRepository extends JpaRepository<OrderEventRecord, String> {

    List<OrderEventRecord> findByOrderIdOrderBySequenceAsc(String orderId);

    @Query("SELECT COALESCE(MAX(e.sequence), 0) FROM OrderEventRecord e WHERE e.orderId = :orderId")
    int findMaxSequenceByOrderId(@Param("orderId") String orderId);

    @Query("SELECT e FROM OrderEventRecord e WHERE e.eventType = :eventType " +
           "AND e.createdAt BETWEEN :from AND :to ORDER BY e.createdAt ASC")
    List<OrderEventRecord> findByEventTypeAndTimeRange(
            @Param("eventType") String eventType,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
