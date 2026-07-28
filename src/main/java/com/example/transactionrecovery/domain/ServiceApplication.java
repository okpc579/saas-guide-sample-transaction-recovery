package com.example.transactionrecovery.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "service_application")
public class ServiceApplication {
    public enum Status { REQUESTED, ACTIVE, CANCELLED }

    @Id public UUID id;
    @Column(nullable = false) public String tenantId;
    @Column(nullable = false) public String planCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false) public Status status;
    @Column(nullable = false) public Instant createdAt;

    protected ServiceApplication() {}

    public ServiceApplication(UUID id, String tenantId, String planCode) {
        this.id = id;
        this.tenantId = tenantId;
        this.planCode = planCode;
        this.status = Status.REQUESTED;
        this.createdAt = Instant.now();
    }
}
