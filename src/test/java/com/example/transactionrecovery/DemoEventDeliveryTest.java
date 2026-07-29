package com.example.transactionrecovery;

import com.example.transactionrecovery.domain.OutboxEvent;
import com.example.transactionrecovery.service.DemoEventDelivery;
import com.example.transactionrecovery.service.SagaEventProcessor;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DemoEventDeliveryTest {
    @Test
    void unexpectedProcessorFailureLeavesEventForLaterDelivery() {
        SagaEventProcessor processor = mock(SagaEventProcessor.class);
        DemoEventDelivery delivery = new DemoEventDelivery(processor);
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "tenant-a", "APPLICATION_CREATED", "{}");
        delivery.send(event);
        when(processor.process(event)).thenThrow(new RuntimeException("unexpected")).thenReturn(true);

        assertThatThrownBy(() -> delivery.consumeAvailable("tenant-a"))
                .isInstanceOf(RuntimeException.class);
        assertThat(delivery.consumeAvailable("tenant-a")).isOne();
        verify(processor, times(2)).process(event);
    }

    @Test
    void successfulProcessingRemovesEvent() {
        SagaEventProcessor processor = mock(SagaEventProcessor.class);
        DemoEventDelivery delivery = new DemoEventDelivery(processor);
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "tenant-a", "APPLICATION_CREATED", "{}");
        delivery.send(event);

        assertThat(delivery.consumeAvailable("tenant-a")).isOne();
        assertThat(delivery.consumeAvailable("tenant-a")).isZero();
        verify(processor).process(event);
    }
}
