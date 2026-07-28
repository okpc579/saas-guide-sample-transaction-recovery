package com.example.transactionrecovery.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
    public enum Status { PENDING, PUBLISHED }

    @Id public UUID id;
    @Column(nullable = false) public String tenantId;
    @Column(nullable = false) public String eventType;
    @Column(nullable = false, length = 2000) public String payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false) public Status status;
    @Column(nullable = false) public Instant occurredAt;

    protected OutboxEvent() {}

    public OutboxEvent(UUID id, String tenantId, String eventType, String payload) {
        this.id = id;
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = Status.PENDING;
        this.occurredAt = Instant.now();
    }
}
