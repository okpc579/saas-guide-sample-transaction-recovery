package com.example.transactionrecovery.service;

import com.example.transactionrecovery.domain.*;
import com.example.transactionrecovery.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ApplicationCommandService {
    public enum DemoScenario { SUCCESS, COMPENSATION, MANUAL_REQUIRED }
    public record StartRequest(String planCode, DemoScenario scenario) {}
    public record Created(UUID applicationId, UUID sagaId, UUID eventId, String status) {}

    private final ApplicationRepository applications;
    private final SagaRepository sagas;
    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public ApplicationCommandService(ApplicationRepository applications, SagaRepository sagas,
                                     OutboxRepository outbox, ObjectMapper json) {
        this.applications = applications;
        this.sagas = sagas;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public Created start(String tenantId, StartRequest request) {
        Objects.requireNonNull(request.scenario(), "scenario is required (demo-only)");
        UUID applicationId = UUID.randomUUID();
        UUID sagaId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        applications.save(new ServiceApplication(applicationId, tenantId, request.planCode()));
        sagas.save(new Saga(sagaId, applicationId, tenantId));
        try {
            String payload = json.writeValueAsString(Map.of(
                    "applicationId", applicationId,
                    "sagaId", sagaId,
                    "scenario", request.scenario()));
            outbox.save(new OutboxEvent(eventId, tenantId, "SERVICE_APPLICATION_CREATED", payload));
        } catch (Exception failure) {
            throw new IllegalStateException("Outbox serialization/storage failed", failure);
        }
        return new Created(applicationId, sagaId, eventId, Saga.Status.STARTED.name());
    }
}
