package com.example.transactionrecovery.repository;

import com.example.transactionrecovery.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findByTenantIdAndStatusOrderByOccurredAt(String tenantId, OutboxEvent.Status status);
}
