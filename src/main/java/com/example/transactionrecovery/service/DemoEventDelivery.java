package com.example.transactionrecovery.service;

import com.example.transactionrecovery.domain.OutboxEvent;
import org.springframework.stereotype.Component;
import java.util.*;

/** Volatile, single-process transport used only to demonstrate publish versus consume. */
@Component
public class DemoEventDelivery {
    private final Queue<OutboxEvent> queue = new ArrayDeque<>();
    private final SagaEventProcessor processor;

    public DemoEventDelivery(SagaEventProcessor processor) {
        this.processor = processor;
    }

    public synchronized void send(OutboxEvent event) {
        queue.add(event);
    }

    public int consumeAvailable(String tenantId) {
        int consumed = 0;
        for (OutboxEvent event = next(tenantId); event != null; event = next(tenantId)) {
            processor.process(event);
            remove(event);
            consumed++;
        }
        return consumed;
    }

    private synchronized OutboxEvent next(String tenantId) {
        for (OutboxEvent event : queue) {
            if (event.tenantId.equals(tenantId)) {
                return event;
            }
        }
        return null;
    }

    private synchronized void remove(OutboxEvent event) {
        queue.remove(event);
    }
}
