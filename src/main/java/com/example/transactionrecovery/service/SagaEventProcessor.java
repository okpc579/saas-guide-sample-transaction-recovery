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
        EventData data = consumerTransaction.execute(ignored -> recordAllocation(event));
        if (data == null) return false;

        if (data.scenario() == ApplicationCommandService.DemoScenario.SUCCESS) {
            steps.completeActivation(event.tenantId, data.sagaId(), data.applicationId());
            return true;
        }

        steps.startCompensation(event.tenantId, data.sagaId());
        for (int attempt = 1; attempt <= 3; attempt++) {
            boolean succeeds = data.scenario() == ApplicationCommandService.DemoScenario.COMPENSATION;
            if (steps.attemptCompensation(event.tenantId, data.sagaId(), data.applicationId(), succeeds)) break;
        }
        return true;
    }

    private EventData recordAllocation(OutboxEvent event) {
        if (processedEvents.existsByConsumerNameAndEventId(CONSUMER, event.id)) return null;
        try {
            JsonNode payload = json.readTree(event.payload);
            UUID sagaId = UUID.fromString(payload.required("sagaId").asText());
            UUID applicationId = UUID.fromString(payload.required("applicationId").asText());
            Saga saga = sagas.findByIdAndTenantId(sagaId, event.tenantId).orElseThrow();
            applications.findByIdAndTenantId(applicationId, event.tenantId).orElseThrow();
            saga.transition(Saga.Status.RESOURCE_ALLOCATED);
            processedEvents.saveAndFlush(new ProcessedEvent(CONSUMER, event.id, event.tenantId));
            return new EventData(sagaId, applicationId,
                    ApplicationCommandService.DemoScenario.valueOf(payload.required("scenario").asText()));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception invalidEvent) {
            throw new IllegalArgumentException("Invalid event", invalidEvent);
        }
    }

    public record EventData(UUID sagaId, UUID applicationId,
                            ApplicationCommandService.DemoScenario scenario) {}
}
