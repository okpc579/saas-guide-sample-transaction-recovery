package com.example.transactionrecovery.tenant;
import org.springframework.stereotype.Component; import org.springframework.web.context.request.NativeWebRequest;
@Component public class TenantContextResolver { public TenantContext resolve(NativeWebRequest r){ String t=r.getHeader("X-Tenant-Id"),u=r.getHeader("X-User-Id"); if(t==null||t.isBlank()) throw new IllegalArgumentException("X-Tenant-Id is required"); return new TenantContext(t,u==null?"demo-user":u); } }
