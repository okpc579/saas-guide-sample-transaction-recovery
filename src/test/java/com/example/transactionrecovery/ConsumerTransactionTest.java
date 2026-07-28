package com.example.transactionrecovery;

import com.example.transactionrecovery.domain.*;
import com.example.transactionrecovery.repository.*;
import com.example.transactionrecovery.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class ConsumerTransactionTest {
    @Autowired ApplicationCommandService commands;
    @Autowired SagaEventProcessor processor;
    @Autowired ApplicationRepository applications;
    @Autowired SagaRepository sagas;
    @Autowired OutboxRepository outbox;
    @SpyBean ProcessedEventRepository processed;

    @BeforeEach
    void clean() {
        processed.deleteAll();
        outbox.deleteAll();
        sagas.deleteAll();
        applications.deleteAll();
    }

    @Test
    void processedEventFailureRollsBackConsumerBusinessChange() {
        var created = commands.start("tenant-a", new ApplicationCommandService.StartRequest(
                "BASIC", ApplicationCommandService.DemoScenario.SUCCESS));
        doThrow(new RuntimeException("simulated processed-event failure"))
                .when(processed).saveAndFlush(any(ProcessedEvent.class));

        assertThatThrownBy(() -> processor.process(outbox.findById(created.eventId()).orElseThrow()))
                .isInstanceOf(RuntimeException.class);
        assertThat(sagas.findById(created.sagaId()).orElseThrow().status).isEqualTo(Saga.Status.STARTED);
        assertThat(processed.count()).isZero();
    }

    @Test
    void uniqueConstraintIsFinalDuplicateDefense() {
        UUID eventId = UUID.randomUUID();
        processed.saveAndFlush(new ProcessedEvent("service-saga", eventId, "tenant-a"));
        assertThatThrownBy(() -> processed.saveAndFlush(
                new ProcessedEvent("service-saga", eventId, "tenant-a")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
