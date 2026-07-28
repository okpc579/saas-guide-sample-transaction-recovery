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
        for (OutboxEvent event = poll(tenantId); event != null; event = poll(tenantId)) {
            processor.process(event);
            consumed++;
        }
        return consumed;
    }

    private synchronized OutboxEvent poll(String tenantId) {
        Iterator<OutboxEvent> events = queue.iterator();
        while (events.hasNext()) {
            OutboxEvent event = events.next();
            if (event.tenantId.equals(tenantId)) {
                events.remove();
                return event;
            }
        }
        return null;
    }
}
