package com.example.transactionrecovery.service;

import com.example.transactionrecovery.domain.OutboxEvent;
import com.example.transactionrecovery.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxPublisher {
    private final OutboxRepository outbox;
    private final DemoEventDelivery delivery;

    public OutboxPublisher(OutboxRepository outbox, DemoEventDelivery delivery) {
        this.outbox = outbox;
        this.delivery = delivery;
    }

    @Transactional
    public int publishPending(String tenantId) {
        int published = 0;
        for (OutboxEvent event : outbox.findByTenantIdAndStatusOrderByOccurredAt(
                tenantId, OutboxEvent.Status.PENDING)) {
            delivery.send(event);
            event.status = OutboxEvent.Status.PUBLISHED;
            published++;
        }
        return published;
    }
}
