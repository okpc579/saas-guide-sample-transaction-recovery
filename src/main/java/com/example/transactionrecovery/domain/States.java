package com.example.transactionrecovery.domain;
public final class States { private States(){} public enum ApplicationStatus{REQUESTED,ACTIVE,CANCELLED} public enum OutboxStatus{PENDING,PUBLISHED,FAILED} public enum SagaStatus{STARTED,RESOURCE_ALLOCATED,ACTIVATING,COMPLETED,COMPENSATING,COMPENSATED,MANUAL_REQUIRED} }
