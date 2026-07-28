package com.example.transactionrecovery.service;

import com.example.transactionrecovery.domain.*;
import com.example.transactionrecovery.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
public class SagaStepService {
    private final SagaRepository sagas;
    private final ApplicationRepository applications;

    public SagaStepService(SagaRepository sagas, ApplicationRepository applications) {
        this.sagas = sagas;
        this.applications = applications;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeActivation(String tenantId, UUID sagaId, UUID applicationId) {
        Saga saga = saga(tenantId, sagaId);
        ServiceApplication application = application(tenantId, applicationId);
        application.status = ServiceApplication.Status.ACTIVE;
        saga.transition(Saga.Status.COMPLETED);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startCompensation(String tenantId, UUID sagaId) {
        saga(tenantId, sagaId).transition(Saga.Status.COMPENSATING);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean attemptCompensation(String tenantId, UUID sagaId, UUID applicationId, boolean succeeds) {
        Saga saga = saga(tenantId, sagaId);
        saga.compensationAttempts++;
        if (succeeds) {
            application(tenantId, applicationId).status = ServiceApplication.Status.CANCELLED;
            saga.transition(Saga.Status.COMPENSATED);
        } else if (saga.compensationAttempts == 3) {
            saga.transition(Saga.Status.MANUAL_REQUIRED);
        }
        return succeeds;
    }

    private Saga saga(String tenantId, UUID id) {
        return sagas.findByIdAndTenantId(id, tenantId).orElseThrow();
    }

    private ServiceApplication application(String tenantId, UUID id) {
        return applications.findByIdAndTenantId(id, tenantId).orElseThrow();
    }
}
