package com.example.transactionrecovery.api;

import com.example.transactionrecovery.domain.Saga;
import com.example.transactionrecovery.repository.SagaRepository;
import com.example.transactionrecovery.service.*;
import com.example.transactionrecovery.tenant.TenantContextResolver;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.NativeWebRequest;
import java.util.*;

@RestController
@RequestMapping("/api/transaction-demo")
public class TransactionDemoController {
    private final TenantContextResolver tenants;
    private final ApplicationCommandService commands;
    private final OutboxPublisher publisher;
    private final DemoEventDelivery delivery;
    private final SagaRepository sagas;

    public TransactionDemoController(TenantContextResolver tenants, ApplicationCommandService commands,
                                     OutboxPublisher publisher, DemoEventDelivery delivery, SagaRepository sagas) {
        this.tenants = tenants;
        this.commands = commands;
        this.publisher = publisher;
        this.delivery = delivery;
        this.sagas = sagas;
    }

    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationCommandService.Created start(@RequestBody ApplicationCommandService.StartRequest request,
                                                   NativeWebRequest web) {
        return commands.start(tenants.resolveTenantId(web), request);
    }

    @PostMapping("/outbox/publish")
    public Map<String, Integer> publish(NativeWebRequest web) {
        String tenantId = tenants.resolveTenantId(web);
        return Map.of("published", publisher.publishPending(tenantId));
    }

    @PostMapping("/events/consume")
    public Map<String, Integer> consume(NativeWebRequest web) {
        String tenantId = tenants.resolveTenantId(web);
        return Map.of("consumed", delivery.consumeAvailable(tenantId));
    }

    @GetMapping("/sagas/{id}")
    public Saga saga(@PathVariable UUID id, NativeWebRequest web) {
        String tenantId = tenants.resolveTenantId(web);
        return sagas.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Saga not found"));
    }
}
