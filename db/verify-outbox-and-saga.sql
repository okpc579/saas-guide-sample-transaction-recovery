-- Run after a Postman scenario. Replace tenant-a when required.
SELECT id, tenant_id, event_type, status, publish_attempts FROM outbox_event ORDER BY occurred_at;
SELECT id, application_id, tenant_id, status, compensation_attempts FROM service_saga WHERE tenant_id = 'tenant-a';
SELECT consumer_name, event_id, tenant_id, processed_at FROM processed_event ORDER BY processed_at;
