# saas-guide-sample-transaction-recovery

## 1. 예제 목적

이 저장소는 SaaS 개발 참조 가이드 4.3의 **Transactional Outbox, 멱등 소비, Saga 보상과 수동 복구 전환**을 독립적으로 실행해 보는 최소 Spring Boot 예제다. 운영용 Saga Framework나 메시징 플랫폼이 아니다.

신청 요청의 `scenario`는 장애 흐름을 재현하는 **demo-only 값**이며 실제 업무 필드가 아니다.

```json
{"planCode":"BASIC","scenario":"SUCCESS"}
```

지원 시나리오는 `SUCCESS`, `COMPENSATION`, `MANUAL_REQUIRED` 세 가지다.

## 2. 로컬 롤백과 보상 트랜잭션의 차이

신청 데이터, `STARTED` Saga, 최초 `PENDING` Outbox는 `ApplicationCommandService.start`의 동일한 로컬 트랜잭션에 저장된다. Outbox 저장이 실패하면 신청과 Saga도 함께 롤백된다.

반면 이벤트 소비에서 자원 할당이 커밋된 뒤 발생한 활성화 실패는 최초 신청 트랜잭션을 롤백할 수 없다. 따라서 Saga를 `COMPENSATING`으로 전환하고, 자원 해제라는 의미상 반대 업무를 별도 로컬 트랜잭션으로 실행한다. 보상 시도 세 번이 모두 실패하면 자동 처리를 멈추고 `MANUAL_REQUIRED`로 전환한다.

## 3. Outbox 발행과 소비의 트랜잭션 경계

```text
TX A: service_application + service_saga(STARTED) + outbox_event(PENDING)
TX B: tenant의 PENDING Outbox를 demo queue에 전송 + PUBLISHED
TX C: (consumer_name,event_id) 중복 확인 + 자원 할당 + processed_event
TX D: 활성화 완료 또는 COMPENSATING 전환
TX E: 각 보상 시도; 성공하면 COMPENSATED, 3회 소진하면 MANUAL_REQUIRED
```

Outbox Event ID는 최초 생성 후 바뀌지 않는다. `PUBLISHED`는 데모 전송 큐에 발행됐다는 뜻일 뿐 소비 완료를 뜻하지 않는다. TX C의 `processed_event`는 최초 신청 생성 이벤트의 **자원 할당 단계가 한 번 적용되었음을 나타내는 멱등 처리 이력**이며, 전체 Saga가 완료됐다는 의미는 아니다. 후속 Saga 단계는 `RESOURCE_ALLOCATED` 또는 `COMPENSATING` 상태를 기준으로 재전달 시 재개할 수 있다. `(consumer_name,event_id)` UNIQUE 제약은 동시 중복 처리의 최종 방어선이다.

모든 Saga/Application/Outbox 처리와 수동 API는 `X-Tenant-Id` 범위로 제한된다.

## 4. Saga 상태 흐름

```text
STARTED → RESOURCE_ALLOCATED → COMPLETED
                         └────→ COMPENSATING → COMPENSATED
                                               └→ MANUAL_REQUIRED
```

`ACTIVATING`은 제거했다. 이 예제에는 활성화 시작만 별도로 커밋하거나 장시간 추적하는 경계가 없으므로, 관찰할 수 없는 절차 중간 상태보다 `RESOURCE_ALLOCATED` 이후의 성공 또는 보상 전환을 직접 보여주는 편이 명확하다.

## 5. API 실행 순서

모든 요청에 `X-Tenant-Id: tenant-a`를 보낸다.

| 순서 | API | 확인 사항 |
|---:|---|---|
| 1 | `POST /api/transaction-demo/applications` | 신청, Saga, PENDING Outbox 원자 저장 |
| 2 | `POST /api/transaction-demo/outbox/publish` | 해당 tenant Outbox의 PUBLISHED 전환 |
| 3 | `POST /api/transaction-demo/events/consume` | 별도 소비/업무 단계/보상 트랜잭션 실행 |
| 4 | `GET /api/transaction-demo/sagas/{sagaId}` | 해당 tenant의 최종 Saga 상태 |

```bash
./mvnw clean test
./mvnw spring-boot:run
```

기본 DB는 `jdbc:h2:file:./data/transaction-recovery`다. PostgreSQL은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 선택할 수 있고 포트는 `SERVER_PORT`로 변경한다.

## 6. Postman 대표 시나리오

`postman/transaction-recovery.postman_collection.json`과 `postman/local.postman_environment.json`을 import한다.

1. 정상 처리: `COMPLETED`
2. 활성화 실패 후 자원 해제 보상: `COMPENSATED`
3. 보상 세 번 실패: `MANUAL_REQUIRED`
4. 다른 tenant의 Saga 조회: 404 `RESOURCE_NOT_FOUND`

publish와 consume 요청은 의도적으로 분리되어 발행 성공과 소비 완료가 다른 시점임을 보여준다.

## 7. 자동 테스트 항목

- 신청/Saga/Outbox 동일 트랜잭션과 고정 Event ID
- Outbox 저장 실패 시 전체 로컬 롤백
- PUBLISHED와 소비 완료 분리
- 동일 Event ID 중복 소비 시 terminal 상태는 무시하고 중단된 후속 단계는 재개
- `RESOURCE_ALLOCATED`, `COMPENSATING` 중단 상태에서 재전달 복구 및 기존 보상 횟수 유지
- processor 예외 시 demo queue 이벤트 유지, 성공 시 제거
- `processed_event`와 `STARTED`가 함께 존재하는 모순 상태 오류
- DB UNIQUE 최종 방어
- 허용되지 않은 Saga 전이 차단
- 소비 이력 저장 실패 시 소비 트랜잭션의 업무 변경 롤백
- tenant별 Outbox 발행·소비 제한
- 정상 완료, 보상 완료, 재시도 소진 후 수동 복구 전환

수동 SQL은 `db/verify-outbox-and-saga.sql`, 데모 데이터 정리는 `db/cleanup-demo-data.sql`을 사용한다.

## 8. 구현하지 않은 범위

Kafka/RabbitMQ, 2PC, exactly-once, 운영용 Outbox Scheduler, 다중 인스턴스 분산 락, 범용 Retry/DLQ API, 지수 backoff/jitter, 운영자 복구 UI, 인증·소속 검증 전체, RLS, 운영 모니터링·감사 체계는 구현하지 않는다.

`DemoEventDelivery`는 processor가 정상 반환한 뒤에만 이벤트를 제거하는 단일 프로세스 교육용 전달 구조다. 예상하지 않은 예외가 나면 이벤트를 큐에 남기고 호출자에게 전파한다. 이는 실제 broker의 durable delivery, ack/nack, redelivery를 구현하거나 대체하지 않는다. 프로세스 자체가 종료되면 인메모리 Queue의 이벤트는 유실된다.

## 9. 운영 적용 시 추가 고려사항

운영 적용에는 durable broker, Outbox claim/lease 및 다중 인스턴스 동시성 제어, Scheduler와 재발행 정책, optimistic locking, 계약 버전 관리, 인증된 tenant context, Secret Manager, 관측·감사 로그, 보존 정책, 운영자 승인 복구 절차가 추가로 필요하다. 다중 인스턴스 동시성, claim/lease, Scheduler는 이 예제의 제외 범위이며, 메시지 전달과 DB 상태 변경 사이의 exactly-once 원자성을 주장하지 않는다.
