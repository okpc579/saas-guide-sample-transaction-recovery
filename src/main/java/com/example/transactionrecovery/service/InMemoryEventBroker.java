package com.example.transactionrecovery.service;
import com.example.transactionrecovery.domain.OutboxEvent; import org.springframework.stereotype.Component; import java.util.*;
/** Demo-only transport: volatile, single-process, and not a production broker. */ @Component public class InMemoryEventBroker {private final Queue<OutboxEvent> queue=new ArrayDeque<>(); public synchronized void send(OutboxEvent event){queue.add(event);} public synchronized OutboxEvent poll(){return queue.poll();} public synchronized int size(){return queue.size();}}
