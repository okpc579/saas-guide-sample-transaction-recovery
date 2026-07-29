# Transaction Recovery 예제

## 1. 예제 목적

이 저장소(`saas-guide-sample-transaction-recovery`)는 SaaS 개발 참조 가이드 4.3의 다음 복구 패턴을 한 개의 Spring Boot 애플리케이션에서 확인하는 교육용 예제다.

- 신청, Saga, Outbox를 한 로컬 트랜잭션으로 저장하는 **Transactional Outbox**
- `(consumer_name, event_id)`로 같은 이벤트의 업무 반영을 한 번으로 제한하는 **멱등 소비**
- 이미 커밋된 업무를 반대 업무로 되돌리는 **Saga 보상**
- 자동 보상 재시도를 소진했을 때의 **수동 복구 전환**

요청의 `scenario`는 장애 흐름을 재현하기 위한 데모 전용 값이다. 실제 업무 필드는 아니다.

| `scenario` | 결과 | 의미 |
|---|---|---|
| `SUCCESS` | `COMPLETED` | 자원 할당과 활성화가 모두 성공한다. |
| `COMPENSATION` | `COMPENSATED` | 활성화 실패 뒤 자원 해제 보상이 첫 시도에 성공한다. |
| `MANUAL_REQUIRED` | `MANUAL_REQUIRED` | 보상이 세 번 실패하여 자동 처리를 중단한다. |

## 2. 확인할 설계 내용

### 로컬 롤백과 보상 트랜잭션의 차이

신청(`service_application`), `STARTED` Saga(`service_saga`), `PENDING` Outbox(`outbox_event`)는 `ApplicationCommandService.start`의 같은 로컬 트랜잭션에 저장된다. Outbox 저장이 실패하면 앞의 신청과 Saga도 함께 롤백된다.

반면 이벤트 소비 중 자원 할당이 커밋된 뒤 활성화가 실패하면 최초 신청 트랜잭션을 되돌릴 수 없다. 이때 Saga를 `COMPENSATING`으로 전환하고 자원 해제라는 의미상 반대 업무를 별도 로컬 트랜잭션으로 실행한다. 보상 시도 세 번이 모두 실패하면 `MANUAL_REQUIRED`가 된다.

### Outbox와 멱등 소비

- Outbox Event ID는 생성 후 바뀌지 않는다.
- `PUBLISHED`는 인메모리 데모 큐에 전송했다는 뜻이며 소비 완료를 뜻하지 않는다.
- `processed_event`는 최초 신청 생성 이벤트의 **자원 할당 단계가 적용됐음**을 기록한다. 전체 Saga 완료 기록은 아니다.
- `(consumer_name, event_id)` UNIQUE 제약이 동시 중복 처리의 최종 방어선이다.
- 재전달 시 terminal 상태는 다시 처리하지 않고, `RESOURCE_ALLOCATED` 또는 `COMPENSATING`에서 멈춘 후속 단계는 이어서 실행한다.
- 모든 처리와 조회는 `X-Tenant-Id` 값으로 범위가 제한된다.

## 3. 구성 요소와 처리 흐름

이 예제는 외부 메시지 브로커 없이 H2 또는 PostgreSQL과 단일 Spring Boot 프로세스로 구성된다. `DemoEventDelivery`의 큐는 프로세스 메모리에만 존재한다.

```text
클라이언트
  │ POST /applications
  ▼
TX A: service_application + service_saga(STARTED) + outbox_event(PENDING)
  │ POST /outbox/publish
  ▼
TX B: tenant의 PENDING Outbox → 인메모리 큐 + outbox_event(PUBLISHED)
  │ POST /events/consume
  ▼
TX C: 중복 확인 + 자원 할당 + processed_event
TX D: 활성화 완료 또는 COMPENSATING 전환
TX E: 보상 시도별 트랜잭션 → COMPENSATED 또는 MANUAL_REQUIRED
  │ GET /sagas/{sagaId}
  ▼
최종 Saga 상태 확인
```

Saga 상태 흐름은 다음과 같다.

```text
STARTED → RESOURCE_ALLOCATED → COMPLETED
                         └────→ COMPENSATING → COMPENSATED
                                               └→ MANUAL_REQUIRED
```

## 4. 사전 준비

- **JDK 21**: `pom.xml`의 Java 버전이다. `java -version`으로 확인한다.
- **Git Bash 또는 Linux 셸**: 아래 명령은 두 환경에서 동일한 Bash 문법을 사용한다.
- **Postman**: 7절을 실행할 때만 필요하다.
- **PostgreSQL과 `psql`**: PostgreSQL을 선택하거나 보조 SQL을 실행할 때만 필요하다. 기본 실행에는 필요 없다.

별도 Maven 설치는 필요하지 않다. 저장소의 Maven Wrapper(`./mvnw`)를 사용한다. Docker Compose 파일은 이 저장소에 없으므로 Docker도 기본 전제 조건이 아니다.

### 단계 1. 저장소와 도구 확인

목적:
현재 디렉터리가 올바른 저장소인지 확인하고 JDK 21을 사용할 수 있는지 확인한다.

명령:

```bash
pwd
git rev-parse --show-toplevel
java -version
./mvnw -version
```

`git rev-parse` 결과의 마지막 디렉터리가 `saas-guide-sample-transaction-recovery`여야 하며, Maven 출력의 Java version은 21이어야 한다.

## 5. 가장 빠른 실행 방법

기본 파일 기반 H2 DB를 사용하면 외부 DB 준비 없이 실행할 수 있다.

### 단계 1. 자동 테스트 실행

목적:
애플리케이션을 시작하기 전에 트랜잭션 롤백, 멱등 소비, Saga 보상과 tenant 제한이 동작하는지 확인한다.

명령:

```bash
./mvnw clean test
```

`BUILD SUCCESS`가 출력되어야 한다.

### 단계 2. 애플리케이션 시작

목적:
Flyway가 기본 H2 DB에 테이블을 생성하게 하고 HTTP API를 8080 포트에 연다.

명령:

```bash
./mvnw spring-boot:run
```

`Started TransactionRecoveryApplication`이 출력되면 실행 준비가 끝난다. 이 터미널은 그대로 두고, 새 Git Bash 또는 Linux 터미널에서 6절의 API 호출이나 7절의 Postman 검증을 진행한다. 종료는 실행 터미널에서 `Ctrl+C`를 누른다.

## 6. 상세 실행 절차

아래 절차는 Postman 없이 `curl`로 정상 흐름을 한 단계씩 관찰한다. 모든 요청에 같은 `X-Tenant-Id: tenant-a`를 보내야 한다.

### 단계 1. 신청과 Outbox 생성

목적:
신청, `STARTED` Saga, `PENDING` Outbox가 한 트랜잭션에서 생성되는 것을 확인한다.

명령:

```bash
curl -i -X POST "http://localhost:8080/api/transaction-demo/applications" \
  -H "X-Tenant-Id: tenant-a" \
  -H "Content-Type: application/json" \
  -d '{"planCode":"BASIC","scenario":"SUCCESS"}'
```

HTTP `201`과 함께 `applicationId`, `sagaId`, `eventId`, `status`가 반환되며 `status`는 `STARTED`다. 다음 단계에서 사용할 수 있도록 응답의 `sagaId`를 복사한다.

### 단계 2. Outbox 발행

목적:
`tenant-a`의 `PENDING` Outbox를 인메모리 큐로 보내고 DB 상태를 `PUBLISHED`로 바꾼다. 발행과 소비가 분리된 경계임을 확인한다.

명령:

```bash
curl -i -X POST "http://localhost:8080/api/transaction-demo/outbox/publish" \
  -H "X-Tenant-Id: tenant-a"
```

새 신청 한 건만 대기 중이었다면 응답은 `{"published":1}`이다. 같은 요청을 다시 실행하면 이미 발행된 Outbox는 대상이 아니므로 `{"published":0}`이다.

### 단계 3. 이벤트 소비

목적:
인메모리 큐의 이벤트를 소비하여 자원 할당 멱등 기록과 후속 Saga 단계를 실행한다.

명령:

```bash
curl -i -X POST "http://localhost:8080/api/transaction-demo/events/consume" \
  -H "X-Tenant-Id: tenant-a"
```

응답은 `{"consumed":1}`이다. 정상 처리 후 큐에서 이벤트가 제거되므로 같은 요청을 다시 실행하면 `{"consumed":0}`이다.

### 단계 4. 최종 Saga 조회

목적:
1단계에서 받은 Saga가 정상 종료 상태인지 확인한다.

명령:

```bash
SAGA_ID="1단계 응답의 sagaId"
curl -i "http://localhost:8080/api/transaction-demo/sagas/${SAGA_ID}" \
  -H "X-Tenant-Id: tenant-a"
```

HTTP `200` 응답의 `status`는 `COMPLETED`여야 한다. 보상 흐름은 1단계 body의 `scenario`를 각각 `COMPENSATION` 또는 `MANUAL_REQUIRED`로 바꾼 뒤 1~4단계를 다시 실행하면 되고, 최종 상태는 각각 `COMPENSATED`, `MANUAL_REQUIRED`다.

## 7. Postman 검증 절차

### 단계 1. Collection과 Environment 가져오기

목적:
저장소에 포함된 실제 요청, 테스트 스크립트, 로컬 변수를 Postman에 등록한다.

가져올 파일:

```text
postman/transaction-recovery.postman_collection.json
postman/local.postman_environment.json
```

Postman의 **Import**에서 두 파일을 선택한 뒤 environment로 **transaction-recovery-local**을 활성화한다. 기본 변수는 `base_url=http://localhost:8080`, `tenant_id=tenant-a`다.

각 시나리오의 `01. 신청 생성` 테스트 스크립트는 응답 값을 다음 environment 변수에 자동 저장한다.

- `application_id`: 생성한 신청 ID
- `saga_id`: 이후 `04. Saga 조회`가 사용하는 Saga ID
- `event_id`: 생성 후에도 유지되는 Outbox Event ID

### 단계 2. `01. 정상 처리` 폴더 실행

목적:
정상 신청이 `COMPLETED`까지 진행되는지 확인한다.

실행 순서:

```text
01. 신청 생성 → 02. Outbox 발행 → 03. 이벤트 소비 → 04. Saga 조회
```

폴더를 **Run folder**로 실행한다. 생성 요청은 `scenario=SUCCESS`를 보내고 세 ID를 저장한다. 발행 요청은 `published=1`, 소비 요청은 `consumed=1`, 마지막 조회는 `status=COMPLETED`인지 테스트한다.

### 단계 3. `02. 활성화 실패 후 보상 완료` 폴더 실행

목적:
활성화 실패 후 별도 보상 트랜잭션이 자원을 해제하는지 확인한다.

실행 순서:

```text
01. 신청 생성 → 02. Outbox 발행 → 03. 이벤트 소비 → 04. Saga 조회
```

생성 요청의 `scenario=COMPENSATION` 때문에 소비 중 보상으로 전환된다. 마지막 요청은 `status=COMPENSATED`인지 확인한다. 새 생성 응답으로 세 environment 변수가 자동 덮어써지므로 수동으로 ID를 복사할 필요가 없다.

### 단계 4. `03. 보상 소진 후 수동 복구` 폴더 실행

목적:
보상 실패가 세 번 누적되면 자동 처리를 멈추는지 확인한다.

실행 순서:

```text
01. 신청 생성 → 02. Outbox 발행 → 03. 이벤트 소비 → 04. Saga 조회
```

생성 요청은 `scenario=MANUAL_REQUIRED`를 사용한다. 마지막 요청은 `status=MANUAL_REQUIRED`인지 확인한다. 이 예제에는 수동 복구 실행 API가 없으므로 상태 확인 후 재처리를 시도하지 말고, 반복 실습 전에 11절에 따라 데이터를 초기화한다.

### 단계 5. `04. 테넌트 격리` 폴더 실행

목적:
다른 tenant가 직전에 생성한 Saga ID를 조회해도 정보가 노출되지 않는지 확인한다.

이 폴더에는 환경의 `tenant_id`를 자동 변경하는 스크립트가 없다. 따라서 1~3번 폴더 중 하나를 끝까지 실행하여 `saga_id`를 저장한 다음, **environment의 `tenant_id`만 `tenant-b`로 변경**하고 `04. 테넌트 격리` 폴더를 실행한다. 요청은 HTTP `404`와 응답 코드 `RESOURCE_NOT_FOUND`를 확인한다.

검증이 끝나면 다음 폴더 실행에 영향을 주지 않도록 `tenant_id`를 반드시 `tenant-a`로 되돌린다. Collection 전체를 한 번에 Runner로 실행하면 이 수동 변수 전환이 일어나지 않으므로 tenant 격리 검증이 성립하지 않는다. 위 폴더 순서와 변수 변경 절차를 사용한다.

## 8. 자동 테스트

### 단계 1. 전체 테스트 실행

목적:
테스트 전용 인메모리 H2 DB에서 Flyway 스키마 검증과 모든 단위·통합 테스트를 실행한다. 테스트 DB는 로컬 실행 DB와 분리된다.

명령:

```bash
./mvnw clean test
```

테스트가 검증하는 범위는 다음과 같다.

- 신청/Saga/Outbox의 원자 저장, 고정 Event ID, Outbox 실패 시 전체 롤백
- `PUBLISHED`와 소비 완료의 분리
- terminal 중복 소비 무시 및 중단된 후속 단계 재개
- `RESOURCE_ALLOCATED`, `COMPENSATING` 재전달 복구와 기존 보상 횟수 유지
- processor 실패 시 큐 이벤트 유지, 성공 시 제거
- 모순 상태와 허용되지 않은 Saga 전이 차단
- 소비 이력 실패 시 소비 트랜잭션의 업무 변경 롤백 및 DB UNIQUE 제약
- tenant별 Outbox 발행·소비·조회 제한
- 정상 완료, 보상 완료, 세 번 실패 후 수동 복구 전환

개별 테스트 클래스만 다시 실행하려면 실제 클래스 이름을 지정한다.

```bash
./mvnw -Dtest=TransactionRecoveryIntegrationTest test
./mvnw -Dtest=ApplicationCommandRollbackTest test
./mvnw -Dtest=ConsumerTransactionTest test
./mvnw -Dtest=DemoEventDeliveryTest test
```

## 9. 설정과 환경변수

`src/main/resources/application.yaml`의 설정과 기본값은 다음과 같다.

| 환경변수 | 기본값 | 용도 |
|---|---|---|
| `DB_URL` | `jdbc:h2:file:./data/transaction-recovery;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE` | JDBC 연결 URL |
| `DB_USERNAME` | `sa` | DB 사용자 |
| `DB_PASSWORD` | 빈 문자열 | DB 암호 |
| `SERVER_PORT` | `8080` | HTTP 포트 |

Git Bash와 Linux 셸에서는 애플리케이션 시작 명령 앞에 값을 설정한다. H2 URL처럼 세미콜론이 있는 값은 반드시 따옴표로 감싼다.

```bash
export SERVER_PORT=8081
export DB_URL='jdbc:h2:file:./data/transaction-recovery;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE'
export DB_USERNAME='sa'
export DB_PASSWORD=''
./mvnw spring-boot:run
```

포트를 변경하면 `curl` URL과 Postman environment의 `base_url`도 같은 포트로 변경한다. 환경변수를 현재 셸에서 제거하려면 다음을 실행한다.

```bash
unset SERVER_PORT DB_URL DB_USERNAME DB_PASSWORD
```

## 10. 데이터베이스·YAML·Docker 관련 절차

### 기본 H2와 Flyway

기본 DB는 저장소 아래 `data/transaction-recovery.mv.db`에 생성되는 파일 기반 H2다. 애플리케이션 시작 시 Flyway가 `src/main/resources/db/migration/V1__create_transaction_recovery_tables.sql`을 자동 적용하고, Hibernate는 `ddl-auto=validate`로 매핑과 스키마를 검증한다. SQL을 수동으로 먼저 적용하지 않는다.

테스트는 `src/test/resources/application-test.yaml`의 별도 인메모리 H2 DB를 사용하므로 실행 데이터에 영향을 주지 않는다.

### 단계 1. PostgreSQL로 선택 실행

목적:
미리 실행 중인 PostgreSQL의 **빈 데이터베이스**를 사용하도록 연결을 전환한다. 이 저장소는 PostgreSQL 컨테이너나 데이터베이스를 생성하지 않는다.

전제:

- PostgreSQL 서버와 대상 DB가 이미 생성되어 있어야 한다.
- 지정한 사용자는 스키마에 테이블과 인덱스, Flyway 이력 테이블을 만들 권한이 있어야 한다.
- 다른 애플리케이션 테이블이 없는 전용 DB 사용을 권장한다.

명령:

```bash
export DB_URL='jdbc:postgresql://localhost:5432/transaction_recovery'
export DB_USERNAME='postgres'
export DB_PASSWORD='postgres'
./mvnw spring-boot:run
```

연결에 성공하면 Flyway가 같은 V1 migration을 자동 적용한다.

### 단계 2. PostgreSQL 데이터 확인

목적:
API 또는 Postman 시나리오 실행 뒤 네 테이블의 tenant별 상태를 확인한다.

전제:
애플리케이션을 PostgreSQL 설정으로 한 번 이상 정상 시작하여 Flyway 적용이 끝나 있어야 하며, `psql`이 설치되어 있어야 한다. 보조 SQL의 조회 tenant는 실제 파일에 적힌 `tenant-a`다. 다른 tenant를 확인하려면 실행 전에 SQL의 조건을 바꾼다.

명령:

```bash
PGPASSWORD="$DB_PASSWORD" psql \
  -h localhost -p 5432 -U "$DB_USERNAME" -d transaction_recovery \
  -f db/verify-outbox-and-saga.sql
```

JDBC URL의 호스트, 포트 또는 DB 이름을 바꿨다면 `psql` 인자도 동일하게 바꾼다.

### YAML과 Docker Compose 범위

애플리케이션 YAML은 위의 DB와 포트 설정만 제공하며 별도의 수동 적용 대상이 아니다. Kubernetes YAML과 Docker Compose 파일은 저장소에 없으므로 적용 절차도 없다.

## 11. 초기화와 정리

### 단계 1. 기본 H2 데이터 완전 초기화

목적:
누적된 신청과 Saga를 모두 제거하고 다음 실행에서 Flyway가 새 DB를 다시 만들게 한다. 인메모리 큐까지 함께 비우기 위해 애플리케이션을 먼저 종료한다.

명령:

```bash
# 실행 중인 애플리케이션 터미널에서 먼저 Ctrl+C
rm -f data/transaction-recovery.mv.db data/transaction-recovery.trace.db
./mvnw spring-boot:run
```

### 단계 2. PostgreSQL 데모 데이터 정리

목적:
Flyway 스키마는 유지하면서 네 업무 테이블의 데모 데이터만 외래 키에 안전한 순서로 삭제한다.

전제:
PostgreSQL 연결 환경변수와 `psql` 조건은 10절과 같으며, 이 SQL은 **tenant 구분 없이 모든 데모 데이터**를 삭제한다. 공유 DB에서는 실행하지 않는다. 실행 전에 애플리케이션을 종료하면 인메모리 큐와 DB가 어긋나는 것을 피할 수 있다.

명령:

```bash
# 실행 중인 애플리케이션 터미널에서 먼저 Ctrl+C
PGPASSWORD="$DB_PASSWORD" psql \
  -h localhost -p 5432 -U "$DB_USERNAME" -d transaction_recovery \
  -f db/cleanup-demo-data.sql
```

실패 시나리오 `MANUAL_REQUIRED` 뒤에는 자동 복구 API가 없다. 같은 레코드로 다시 실습하려 하지 말고 위 H2 초기화 또는 PostgreSQL cleanup을 실행한다.

## 12. 구현하지 않은 범위

다음은 의도적으로 구현하지 않는다.

- Kafka/RabbitMQ와 durable delivery, 2PC, exactly-once
- 운영용 Outbox Scheduler, 범용 Retry/DLQ API, 지수 backoff/jitter
- 다중 인스턴스 claim/lease, 분산 락, optimistic locking
- 운영자 수동 복구 API/UI
- 인증·tenant 소속 검증 전체, RLS
- 운영 모니터링, 감사와 보존 체계
- Docker Compose, 컨테이너 이미지, Kubernetes 배포 YAML

`DemoEventDelivery`는 processor가 정상 반환한 뒤에만 이벤트를 제거한다. 예상하지 않은 예외가 발생하면 큐에 남겨 다음 호출에서 다시 처리하지만, 프로세스가 종료되면 인메모리 이벤트는 유실된다. 실제 broker의 ack/nack 또는 redelivery를 대체하지 않는다.

## 13. 운영 적용 시 추가 고려사항

운영 적용에는 durable broker, Outbox claim/lease와 다중 인스턴스 동시성 제어, Scheduler와 재발행 정책, 지수 backoff/jitter, optimistic locking, 이벤트 계약 버전 관리가 필요하다. 또한 인증된 tenant context, Secret Manager, 관측·감사 로그, 데이터 보존 정책, 운영자 승인과 수동 복구 절차를 마련해야 한다.

특히 메시지 전송과 DB의 `PUBLISHED` 변경 사이에는 원자적 exactly-once 보장이 없다. 운영 설계는 중복 발행과 재전달을 전제로 해야 한다.

## 14. 문제 해결

### `java` 버전 또는 Maven 컴파일 오류

`java -version`과 `./mvnw -version`에서 모두 Java 21이 선택됐는지 확인한다. 다른 버전이면 `JAVA_HOME`을 JDK 21 설치 경로로 지정한 뒤 새 셸에서 다시 실행한다.

### `Port 8080 was already in use`

8080을 사용하는 프로세스를 종료하거나 다른 포트를 사용한다.

```bash
SERVER_PORT=8081 ./mvnw spring-boot:run
```

이 경우 `curl`과 Postman의 `base_url`도 `http://localhost:8081`로 바꾼다.

### `Connection refused` 또는 Postman 요청 실패

애플리케이션 터미널에 `Started TransactionRecoveryApplication`이 있는지, 요청 URL 포트가 `SERVER_PORT`와 같은지 확인한다. 시작 터미널이 종료됐다면 인메모리 큐도 사라졌으므로 신청 생성부터 다시 실행한다.

### `published: 0` 또는 `consumed: 0`

`published: 0`은 해당 tenant에 `PENDING` Outbox가 없다는 뜻이다. 먼저 같은 `X-Tenant-Id`로 신청을 생성한다. `consumed: 0`은 해당 tenant의 인메모리 큐에 이벤트가 없다는 뜻이므로 같은 tenant로 발행 단계를 먼저 실행한다. 서버를 발행 후 소비 전에 재시작했다면 DB는 `PUBLISHED`지만 큐는 유실되므로 11절에서 초기화하고 신청 생성부터 다시 진행한다.

### Saga 조회가 `404 RESOURCE_NOT_FOUND`

`sagaId`가 생성 응답 값인지, 조회의 `X-Tenant-Id`가 생성 때와 같은지 확인한다. Postman tenant 격리 검증 뒤에는 environment의 `tenant_id`를 `tenant-a`로 복원한다.

### PostgreSQL 시작 시 Flyway 또는 권한 오류

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`가 실행 중인 PostgreSQL과 일치하는지 확인한다. 대상 DB가 존재하는지와 사용자가 테이블·인덱스·Flyway 이력 테이블을 생성할 권한이 있는지도 확인한다. 이미 다른 스키마가 들어 있는 DB 대신 빈 전용 DB에서 다시 실행한다.
