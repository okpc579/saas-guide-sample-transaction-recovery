# saas-guide-sample-transaction-recovery

## 예제 개요

이 저장소는 **4.3 Outbox와 Saga 기반 실패 복구**를 독립적으로 학습하기 위한 Spring Boot 참조 예제다. 참고 기준은 `okpc579/saas-sample`의 `65192b16d3382076631d11f73daa8aeccef9458d`이며, 통합 저장소를 복사하지 않고 장의 설계 의도만 최소 도메인으로 재구성했다. 운영용 SaaS나 완성된 분산 트랜잭션 솔루션이 아니다.

> 분석 환경에서는 GitHub 연결이 403으로 차단되어 대상 SHA를 직접 checkout/diff하지 못했다. 따라서 제공된 4.3 요구사항(상태, 흐름, 검증 항목)을 보수적으로 구현 기준으로 삼았다.

## 핵심 설계 개념과 트랜잭션 경계

* `ApplicationCommandService.start`의 **단일 로컬 트랜잭션**이 신청, `STARTED` Saga, `PENDING` Outbox를 저장한다. Outbox 저장이 실패하면 모두 롤백된다.
* Outbox에는 한번 생성한 Event ID와 tenant ID, payload가 보존된다. `OutboxPublisher`는 별도 트랜잭션에서 demo-only 인메모리 전송 후 `PUBLISHED`로 바꾼다. 따라서 발행 성공은 소비 성공을 뜻하지 않는다.
* 소비자는 또 다른 로컬 트랜잭션에서 `(consumer_name, event_id)` 처리 이력의 사전 검사와 DB 유일성 제약을 사용한다.
* Saga는 허용 전이만 명시한다: `STARTED → RESOURCE_ALLOCATED → ACTIVATING → COMPLETED` 또는 `ACTIVATING → COMPENSATING → COMPENSATED | MANUAL_REQUIRED`.
* 활성화 업무 실패는 보상을 시작한다. 자원 해제 기술 오류는 최대 3회 재시도하며 소진하면 `MANUAL_REQUIRED`다.
* 헤더로 확정된 최소 Tenant Context(`tenantId`, `userId`)만 사용하며 요청 body에는 tenant ID가 없다. Saga 조회는 `(id, tenant_id)` 조건을 사용해 다른 tenant의 존재를 404로 숨긴다.

```text
POST 신청
→ [로컬 TX] service_application + service_saga + PENDING outbox_event
→ [별도 TX] Outbox 발행/PUBLISHED (인메모리 큐)
→ [별도 TX] 멱등 소비/processed_event
→ 자원 할당 → 활성화
                    ├ 성공 → COMPLETED
                    └ 업무 실패 → COMPENSATING → 자원 해제 재시도
                                                   ├ 성공 → COMPENSATED
                                                   └ 3회 실패 → MANUAL_REQUIRED
```

## 포함/제외 범위

포함: 서비스 신청, Transactional Outbox, 명시적 Saga, 멱등 소비, 제한적 보상 재시도, tenant 범위 조회, H2/PostgreSQL 호환 Flyway, 자동 테스트, 수동 SQL, Postman.

제외: 3.2의 상세 인증/소속 관리, 3.3 공유 스키마/RLS, 4.2 범용 DLQ API, Kafka/RabbitMQ, 2PC, exactly-once, 운영 스케줄러·분산 락·복구 UI. 인메모리 큐는 교육/자동 테스트 전용이며 프로세스 종료 시 유실되고, 운영 브로커나 실제 retry/backoff/broker DLQ를 대체하지 않는다.

## 기술 환경과 구조

* Java 21, Spring Boot 3.4.7, Maven
* Spring Web, Spring Data JPA, Flyway
* 기본 로컬 H2 파일 DB / 선택적 PostgreSQL / 테스트 H2 메모리 DB

```text
api/                    최소 실행 및 조회 API, 공통 오류 응답
 tenant/                 헤더 기반 최소 Tenant Context
 domain/                 신청, Saga, Outbox, 처리 이력과 상태 전이
 repository/             tenant 조건 및 상태 조건 저장소
 service/                생성 TX, 발행 TX, 소비 TX, 보상 흐름
 db/migration/V1...sql   독립 스키마
 db/                     수동 조회/정리 SQL
 postman/                4.3 전용 Collection과 Environment
```

## 실행과 환경변수

```bash
./mvnw clean test
./mvnw spring-boot:run
```

기본 실행은 외부 구성요소 없이 `./data/transaction-recovery` H2 파일 DB를 쓴다. PostgreSQL을 사용하려면 빈 DB를 준비한 뒤 다음과 같이 실행한다.

```bash
export DB_URL='jdbc:postgresql://localhost:5432/saas_sample'
export DB_USERNAME='saas_sample_app'
export DB_PASSWORD='local-demo-password'
./mvnw spring-boot:run
```

| 변수 | 기본값 | 설명 |
|---|---|---|
| `DB_URL` | `jdbc:h2:file:./data/transaction-recovery;...` | JDBC URL |
| `DB_USERNAME` | `sa` | 로컬 데모 DB 사용자 |
| `DB_PASSWORD` | 빈 값 | 로컬 데모 비밀번호(운영 비밀 아님) |
| `SERVER_PORT` | `8080` | HTTP 포트 |

JWT 비밀 환경변수는 없다. 인증 자체가 4.3의 주제가 아니므로 외부 인증 대신 demo-only 검증 헤더를 최소 구현했다.

## API와 실행 순서

모든 요청은 `X-Tenant-Id: tenant-a`, 선택적으로 `X-User-Id: user-a`를 보낸다.

| 순서 | API | 역할/기대 결과 |
|---:|---|---|
| 1 | `POST /api/transaction-demo/applications` | 201; 신청/Saga/PENDING Outbox 원자 저장 |
| 2 | `POST /api/transaction-demo/outbox/publish` | PENDING 발행 및 PUBLISHED 전이 |
| 3 | `POST /api/transaction-demo/events/consume` | 큐 소비 및 Saga 처리 |
| 4 | `GET /api/transaction-demo/sagas/{sagaId}` | 현재 tenant의 Saga 상태 |

신청 body는 `{"planCode":"BASIC","activationShouldFail":false,"compensationFailures":0}`다. `activationShouldFail`와 `compensationFailures`는 장애 시나리오를 재현하는 **demo-only Failure Plan**이다.

| 검증 | 기대 결과 |
|---|---|
| 정상 | `COMPLETED` |
| 활성화 실패, 보상 중 1회 기술 실패 | 두 번째 보상 성공, `COMPENSATED` |
| 보상 3회 실패 | `MANUAL_REQUIRED` |
| 같은 Event ID 재소비 | 업무 중복 없이 `processed_event` 1건 |
| 잘못된 Saga 전이 | `IllegalStateException`/API에서는 409 `INVALID_STATE_TRANSITION` |
| 다른 tenant Saga 조회 | 404 `RESOURCE_NOT_FOUND` |
| tenant 헤더 누락 | 400 `INVALID_REQUEST` |
| Outbox 저장 실패 | 신청과 Saga까지 롤백(자동 테스트 전용) |

## YAML 및 SQL

`application.yaml`은 애플리케이션명, H2 기본 연결, JPA validate, Flyway와 포트를 정의한다. `application-test.yaml`은 격리된 H2 메모리 DB다. `V1__create_transaction_recovery_tables.sql`은 Migration을 독립적으로 V1부터 시작하며 네 테이블, 상태 CHECK, FK, 유일성 및 조회 인덱스를 만든다. PostgreSQL 전용 RLS는 4.3 범위가 아니므로 없다. H2와 PostgreSQL 모두 동일한 portable DDL/흐름을 사용하지만 운영 PostgreSQL의 락·동시 발행 특성은 이 테스트가 검증하지 않는다.

수동 확인은 애플리케이션 DB에 `db/verify-outbox-and-saga.sql`을 실행하고, 초기화는 `db/cleanup-demo-data.sql`을 실행한다.

## Postman

1. `postman/transaction-recovery.postman_collection.json`과 `postman/local.postman_environment.json`을 import한다.
2. `transaction-recovery-local` Environment를 선택하고 애플리케이션을 실행한다.
3. 폴더를 `01. 정상 처리 → 02. 보상 처리 → 03. 수동 복구 필요 → 04. 테넌트 격리` 순으로 실행한다.
4. 생성 응답의 `application_id`, `saga_id`, `event_id`가 자동 저장된다. 스크립트는 HTTP 상태, 발행/소비 건수, 최종 상태 및 격리 오류 코드를 검증한다.
5. 04 폴더 전에는 `tenant_id`를 `tenant-b`로 바꾸고, 이후 되돌린다.

Postman은 정상/보상/수동복구 흐름을 확인한다. 원자적 롤백, 실제 중복 소비 호출, 허용되지 않은 전이는 위험한 테스트 API를 만들지 않고 자동 테스트에서만 검증한다. 외부 DB가 필수인 Postman 요청은 없다.

## 주요 테이블

| 테이블 | 책임 |
|---|---|
| `service_application` | tenant 소유 신청과 업무 상태 |
| `service_saga` | Saga 상태/보상 시도 횟수 |
| `outbox_event` | 고정 Event ID, payload, 발행 상태 |
| `processed_event` | `(consumer_name,event_id)` 멱등 최종 방어선 |

## 참고 구현과의 차이 및 운영 고려사항

독립성과 가독성을 위해 단일 업무 흐름, 헤더 Tenant Context, 동기 수동 publish/consume endpoint, 인메모리 큐, 고정 3회 즉시 재시도를 택했다. 원본의 클래스명/패키지/Migration 번호/API를 유지하려 하지 않았으며 제공 요구사항의 상태와 트랜잭션 의미를 유지했다.

운영 적용에는 OAuth2 외부 인증과 서버 측 tenant 권한 검증, Secret Manager, 실제 durable broker, polling scheduler, `SKIP LOCKED`/lease 같은 다중 인스턴스 Outbox claim, 지수 backoff와 jitter, FAILED 재발행 정책, optimistic locking, 관측/감사 로그, 운영자 승인 복구 절차, 데이터 보존·보안·고가용성, poison event 처리와 계약 버전 관리가 필요하다. 이 예제는 exactly-once나 완전한 분산 원자성을 주장하지 않는다.
