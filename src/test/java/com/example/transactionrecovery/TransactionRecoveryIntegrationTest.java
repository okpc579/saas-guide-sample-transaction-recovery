package com.example.transactionrecovery;

import com.example.transactionrecovery.domain.*;
import com.example.transactionrecovery.repository.*;
import com.example.transactionrecovery.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class TransactionRecoveryIntegrationTest {
    @Autowired ApplicationCommandService commands;
    @Autowired OutboxPublisher publisher;
    @Autowired DemoEventDelivery delivery;
    @Autowired SagaEventProcessor processor;
    @Autowired ApplicationRepository applications;
    @Autowired SagaRepository sagas;
    @Autowired OutboxRepository outbox;
    @Autowired ProcessedEventRepository processed;

    @BeforeEach
    void clean() {
        processed.deleteAll();
        outbox.deleteAll();
        sagas.deleteAll();
        applications.deleteAll();
    }

    @Test
    void applicationSagaAndPendingOutboxAreCreatedTogetherWithStableEventId() {
        var created = start("tenant-a", ApplicationCommandService.DemoScenario.SUCCESS);
        assertThat(applications.findById(created.applicationId())).isPresent();
        assertThat(sagas.findById(created.sagaId()).orElseThrow().status).isEqualTo(Saga.Status.STARTED);
        assertThat(outbox.findById(created.eventId()).orElseThrow().status).isEqualTo(OutboxEvent.Status.PENDING);

        publisher.publishPending("tenant-a");
        OutboxEvent published = outbox.findById(created.eventId()).orElseThrow();
        assertThat(published.id).isEqualTo(created.eventId());
        assertThat(published.status).isEqualTo(OutboxEvent.Status.PUBLISHED);
    }

    @Test
    void publishedDoesNotMeanConsumedAndDuplicateEventIsIgnored() {
        var created = start("tenant-a", ApplicationCommandService.DemoScenario.SUCCESS);
        assertThat(publisher.publishPending("tenant-a")).isOne();
        assertThat(sagas.findById(created.sagaId()).orElseThrow().status).isEqualTo(Saga.Status.STARTED);
        assertThat(delivery.consumeAvailable("tenant-a")).isOne();
        assertThat(sagas.findById(created.sagaId()).orElseThrow().status).isEqualTo(Saga.Status.COMPLETED);
        assertThat(processor.process(outbox.findById(created.eventId()).orElseThrow())).isFalse();
        assertThat(processed.count()).isOne();
    }

    @Test
    void activationFailureIsCompensatedByCancellingAllocatedApplication() {
        var created = start("tenant-a", ApplicationCommandService.DemoScenario.COMPENSATION);
        publisher.publishPending("tenant-a");
        delivery.consumeAvailable("tenant-a");
        Saga saga = sagas.findById(created.sagaId()).orElseThrow();
        assertThat(saga.status).isEqualTo(Saga.Status.COMPENSATED);
        assertThat(saga.compensationAttempts).isOne();
        assertThat(applications.findById(created.applicationId()).orElseThrow().status)
                .isEqualTo(ServiceApplication.Status.CANCELLED);
    }

    @Test
    void exhaustedCompensationMovesToManualRecovery() {
        var created = start("tenant-a", ApplicationCommandService.DemoScenario.MANUAL_REQUIRED);
        publisher.publishPending("tenant-a");
        delivery.consumeAvailable("tenant-a");
        Saga saga = sagas.findById(created.sagaId()).orElseThrow();
        assertThat(saga.status).isEqualTo(Saga.Status.MANUAL_REQUIRED);
        assertThat(saga.compensationAttempts).isEqualTo(3);
    }

    @Test
    void outboxPublishingAndConsumptionAreTenantScoped() {
        var tenantA = start("tenant-a", ApplicationCommandService.DemoScenario.SUCCESS);
        var tenantB = start("tenant-b", ApplicationCommandService.DemoScenario.SUCCESS);
        assertThat(publisher.publishPending("tenant-a")).isOne();
        assertThat(outbox.findById(tenantB.eventId()).orElseThrow().status).isEqualTo(OutboxEvent.Status.PENDING);
        assertThat(delivery.consumeAvailable("tenant-b")).isZero();
        delivery.consumeAvailable("tenant-a");
        assertThat(sagas.findById(tenantA.sagaId()).orElseThrow().status).isEqualTo(Saga.Status.COMPLETED);
        assertThat(sagas.findByIdAndTenantId(tenantA.sagaId(), "tenant-b")).isEmpty();
    }

    @Test
    void invalidTransitionIsBlocked() {
        Saga saga = new Saga(UUID.randomUUID(), UUID.randomUUID(), "tenant-a");
        assertThatThrownBy(() -> saga.transition(Saga.Status.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
    }

    private ApplicationCommandService.Created start(String tenantId,
                                                     ApplicationCommandService.DemoScenario scenario) {
        return commands.start(tenantId, new ApplicationCommandService.StartRequest("BASIC", scenario));
    }
}
