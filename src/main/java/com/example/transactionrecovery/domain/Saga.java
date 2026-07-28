package com.example.transactionrecovery.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "service_saga")
public class Saga {
    public enum Status { STARTED, RESOURCE_ALLOCATED, COMPLETED, COMPENSATING, COMPENSATED, MANUAL_REQUIRED }

    private static final Map<Status, Set<Status>> ALLOWED = Map.of(
            Status.STARTED, Set.of(Status.RESOURCE_ALLOCATED),
            Status.RESOURCE_ALLOCATED, Set.of(Status.COMPLETED, Status.COMPENSATING),
            Status.COMPENSATING, Set.of(Status.COMPENSATED, Status.MANUAL_REQUIRED));

    @Id public UUID id;
    @Column(nullable = false) public UUID applicationId;
    @Column(nullable = false) public String tenantId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) public Status status;
    @Column(nullable = false) public int compensationAttempts;
    @Column(nullable = false) public Instant updatedAt;

    protected Saga() {}

    public Saga(UUID id, UUID applicationId, String tenantId) {
        this.id = id;
        this.applicationId = applicationId;
        this.tenantId = tenantId;
        this.status = Status.STARTED;
        this.updatedAt = Instant.now();
    }

    public void transition(Status next) {
        if (!ALLOWED.getOrDefault(status, Set.of()).contains(next)) {
            throw new IllegalStateException("Transition " + status + " -> " + next + " is not allowed");
        }
        status = next;
        updatedAt = Instant.now();
    }
}
