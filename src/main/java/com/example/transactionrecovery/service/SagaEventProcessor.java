package com.example.transactionrecovery.service;

import com.example.transactionrecovery.domain.*;
import com.example.transactionrecovery.repository.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Service
public class SagaEventProcessor {
    static final String CONSUMER = "service-saga";

    private final SagaRepository sagas;
    private final ApplicationRepository applications;
    private final ProcessedEventRepository processedEvents;
    private final SagaStepService steps;
    private final ObjectMapper json;
    private final TransactionTemplate consumerTransaction;

    public SagaEventProcessor(SagaRepository sagas, ApplicationRepository applications,
                              ProcessedEventRepository processedEvents, SagaStepService steps,
                              ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.sagas = sagas;
        this.applications = applications;
        this.processedEvents = processedEvents;
        this.steps = steps;
        this.json = json;
        this.consumerTransaction = new TransactionTemplate(transactionManager);
    }

    public boolean process(OutboxEvent event) {
        EventData data = readEventData(event);
        Saga.Status status = consumerTransaction.execute(ignored -> recordOrReadAllocation(event, data));

        if (isTerminal(status)) return false;

        if (status == Saga.Status.RESOURCE_ALLOCATED
                && data.scenario() == ApplicationCommandService.DemoScenario.SUCCESS) {
            steps.completeActivation(event.tenantId, data.sagaId(), data.applicationId());
            return true;
        }

        if (status == Saga.Status.RESOURCE_ALLOCATED) {
            steps.startCompensation(event.tenantId, data.sagaId());
            status = Saga.Status.COMPENSATING;
        }
        if (status != Saga.Status.COMPENSATING) {
            throw new IllegalStateException("Cannot resume saga " + data.sagaId() + " from " + status);
        }

        int attempts = sagas.findByIdAndTenantId(data.sagaId(), event.tenantId)
                .orElseThrow().compensationAttempts;
        if (attempts >= 3) {
            throw new IllegalStateException("Compensating saga has no attempts remaining: " + data.sagaId());
        }
        for (int attempt = attempts; attempt < 3; attempt++) {
            boolean succeeds = data.scenario() == ApplicationCommandService.DemoScenario.COMPENSATION;
            if (steps.attemptCompensation(event.tenantId, data.sagaId(), data.applicationId(), succeeds)) break;
        }
        return true;
    }

    private EventData readEventData(OutboxEvent event) {
        try {
            JsonNode payload = json.readTree(event.payload);
            UUID sagaId = UUID.fromString(payload.required("sagaId").asText());
            UUID applicationId = UUID.fromString(payload.required("applicationId").asText());
            return new EventData(sagaId, applicationId,
                    ApplicationCommandService.DemoScenario.valueOf(payload.required("scenario").asText()));
        } catch (Exception invalidEvent) {
            throw new IllegalArgumentException("Invalid event", invalidEvent);
        }
    }

    private Saga.Status recordOrReadAllocation(OutboxEvent event, EventData data) {
        Saga saga = sagas.findByIdAndTenantId(data.sagaId(), event.tenantId).orElseThrow();
        ServiceApplication application = applications.findByIdAndTenantId(data.applicationId(), event.tenantId)
                .orElseThrow();
        if (!saga.applicationId.equals(application.id)) {
            throw new IllegalStateException("Event application does not belong to saga " + saga.id);
        }

        boolean alreadyProcessed = processedEvents.existsByConsumerNameAndEventId(CONSUMER, event.id);
        if (!alreadyProcessed) {
            if (saga.status != Saga.Status.STARTED) {
                throw new IllegalStateException("Unprocessed event requires STARTED saga, but was " + saga.status);
            }
            saga.transition(Saga.Status.RESOURCE_ALLOCATED);
            processedEvents.saveAndFlush(new ProcessedEvent(CONSUMER, event.id, event.tenantId));
        } else if (saga.status == Saga.Status.STARTED) {
            throw new IllegalStateException("Processed event cannot have STARTED saga " + saga.id);
        }
        return saga.status;
    }

    private boolean isTerminal(Saga.Status status) {
        return status == Saga.Status.COMPLETED || status == Saga.Status.COMPENSATED
                || status == Saga.Status.MANUAL_REQUIRED;
    }

    public record EventData(UUID sagaId, UUID applicationId,
                            ApplicationCommandService.DemoScenario scenario) {}
}
