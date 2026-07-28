package com.example.transactionrecovery.tenant;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.NativeWebRequest;

@Component
public class TenantContextResolver {
    public String resolveTenantId(NativeWebRequest request) {
        String tenantId = request.getHeader("X-Tenant-Id");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("X-Tenant-Id is required");
        }
        return tenantId;
    }
}
