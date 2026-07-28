package com.example.transactionrecovery;

import com.example.transactionrecovery.repository.*;
import com.example.transactionrecovery.service.ApplicationCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationCommandRollbackTest {
    @Autowired ApplicationCommandService commands;
    @Autowired ApplicationRepository applications;
    @Autowired SagaRepository sagas;
    @Autowired ProcessedEventRepository processed;
    @SpyBean OutboxRepository outbox;

    @Test
    void outboxFailureRollsBackWholeLocalTransaction() {
        processed.deleteAll();
        outbox.deleteAll();
        sagas.deleteAll();
        applications.deleteAll();
        doThrow(new RuntimeException("simulated storage failure")).when(outbox).save(any());

        assertThatThrownBy(() -> commands.start("tenant-a", new ApplicationCommandService.StartRequest(
                "BASIC", ApplicationCommandService.DemoScenario.SUCCESS)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(applications.count()).isZero();
        assertThat(sagas.count()).isZero();
        assertThat(outbox.count()).isZero();
    }
}
