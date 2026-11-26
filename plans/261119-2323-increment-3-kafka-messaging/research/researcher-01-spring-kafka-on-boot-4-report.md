# Research: Spring Kafka on Boot 4.1.2 for Increment 3

Tags: `[V]` verified on fetched official page, `[S]` search snippet only (secondary), `[U]` unverified (memory or no source). Budget ~13 lookups, over the 8-10 target because the Boot Kafka page and the Spring Kafka reference index did not render body text.

## TL;DR
- Starter: `spring-boot-starter-kafka` (main). Test: `spring-boot-starter-kafka-test` [S]. Plain `spring-kafka` may not activate Boot auto-config [S, user issue report].
- Serializers: use `JacksonJsonSerializer` / `JacksonJsonDeserializer` (Jackson 3). Do not use the old `JsonSerializer` names.
- Broker: `apache/kafka` (JVM) pinned tag, KRaft, two listeners (`kafka:19092` in-network, `localhost:9092` host).
- Tests: `org.testcontainers:testcontainers-kafka` 2.0.1, `KafkaContainer` with `apache/kafka-native` tag matching compose. `@ServiceConnection` probably works [U on Boot 4.1]; fallback `@DynamicPropertySource`.
- Consumer: keep default `BATCH` ack + `DefaultErrorHandler` with a bounded backoff + `DeadLetterPublishingRecoverer`. Blocking retry, not `@RetryableTopic`, to keep per-payment order.
- Producer: transactional outbox, not "send after DB commit". Kafka transactions chained with DB tx: avoid.

---

## Q1. Dependencies on Boot 4.1.2

| Item | Value | Status |
|---|---|---|
| Main starter | `org.springframework.boot:spring-boot-starter-kafka` | [S] Boot 4.0.2 user report: plain `spring-kafka` did not activate `spring.kafka.*` auto-config; starter fixed it. Spring Kafka issue page. Not restated on 4.1 page. |
| Test starter | `org.springframework.boot:spring-boot-starter-kafka-test` | [S] Spring Kafka "Testing" page says use this with Boot rather than bare `spring-kafka-test`. Exact artifact name not seen on a fetched page. |
| Spring Kafka version | 4.1.2 is current stable line (version selector) | [V] Spring Kafka reference index. |
| Kafka clients version managed by Boot 4.1.2 | not stated on fetched pages | [U] Check Boot "Dependency Versions" appendix. Spring Kafka docs link Kafka 4.2 javadocs, so expect Kafka 4.x clients. |
| Config prefix | `spring.kafka.*` (bootstrap-servers, consumer.group-id, producer/consumer serializers, listener.*) | [V] Boot Kafka page examples. |
| Container factory props | `spring.kafka.listener.*` | [V] Boot Kafka page text. |
| Ack-mode property key | `spring.kafka.listener.ack-mode` | [S] Spring Kafka index page names it. Not seen in body. |
| JSON serializer classes | `org.springframework.kafka.support.serializer.JacksonJsonSerializer`, `JacksonJsonDeserializer`, `JacksonJsonSerde` | [V] Boot Kafka page and Spring Kafka usage. |
| Boot 4 Jackson | Boot 4 defaults to Jackson 3 (`tools.jackson`). Jackson 2 auto-config deprecated. | [S] Spring blog 2025-10-07. |
| Jackson 3 rename | Jackson's own `JsonSerializer` to `ValueSerializer`, `JsonDeserializer` to `ValueDeserializer`, `JsonProcessingException` to `JacksonException` (now RuntimeException) | [S] stevenpg.com migration guide. This is Jackson's API, not Spring Kafka's class names. |
| Spring Kafka class names vs Jackson rename | Spring Kafka uses `JacksonJson*` names in its own docs. Whether the old `JsonSerializer` still exists or is deprecated in 4.1 | [U] Contradiction-free but not confirmed. Do not use it. |
| Starter renames | `spring-boot-starter-web` to `-webmvc`; test starters split (`-webmvc-test`, `-restclient-test`) | [S] Project README table, Boot 4 migration posts. |
| Test starter contents | Boot 4.0 "Test scope dependencies" lists only `spring-boot-starter-test`, which includes Awaitility | [V] docs.spring.io Boot 4.0 page. |

Notes:
- Project already uses split starters (`-webmvc-test`, `-data-jpa-test`). Whether Awaitility arrives transitively through the split test starters is not stated. Check `mvn dependency:tree -Dincludes=org.awaitility` before adding it.
- Add `spring-boot-starter-kafka` and `spring-boot-starter-kafka-test` [S] to `gateway/pom.xml`. Versions come from the Boot BOM. Do not pin Kafka versions unless needed.

## Q2. Local broker in docker compose (KRaft, no ZooKeeper)

Image choice (ranked):
1. `apache/kafka:<pinned 4.x tag>` (JVM). Official image. Easiest to debug and matches Apache docs. [V] Docker's Kafka guide and Apache examples use it. Exact tag [U]: check Docker Hub.
2. `apache/kafka-native:<same tag>`. Faster startup (GraalVM native). [V] Docker guide note. Use only if startup time hurts. Testcontainers uses this image; see Q3.
3. Confluent `cp-kafka`. Works, but adds Confluent-specific env and tooling. Avoid for a learning project.

Single-node env (verify against Apache's `docker/examples/jvm/single-node/plaintext` compose file):

```yaml
services:
  kafka:
    image: apache/kafka:4.1.0   # [U] pin to the tag you verify on Docker Hub
    hostname: kafka
    ports:
      - "9092:9092"             # host listener only
    environment:
      KAFKA_NODE_ID: 1                                           # [U]
      KAFKA_PROCESS_ROLES: broker,controller                     # [U]
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER                # [U]
      KAFKA_LISTENERS: PLAINTEXT://:19092,PLAINTEXT_HOST://:9092,CONTROLLER://:9093   # [U]
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:19092,PLAINTEXT_HOST://localhost:9092  # [U]
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT  # [U]
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093               # [U]
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT                # [U]
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1                  # [V] Apache example sets this
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1          # [U]
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1                     # [U]
      CLUSTER_ID: <fixed-uuid>                                   # [V] optional; fixed ID avoids volume mismatch
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1"]  # [U] path and interval
      interval: 10s
      timeout: 10s
      retries: 10
```

Listener logic:
- `PLAINTEXT://kafka:19092`: container-to-container (gateway container uses `spring.kafka.bootstrap-servers=kafka:19092`).
- `PLAINTEXT_HOST://localhost:9092`: host tools and `mvn spring-boot:run` (`localhost:9092`).
- `CONTROLLER`: internal KRaft quorum, not advertised.
- Caveat [S]: a secondhand write-up says the image script rejects `KAFKA_ADVERTISED_LISTENERS` on controller-only nodes. Single node is broker+controller, so not affected [U].
- If the gateway runs on the host, only `localhost:9092` matters.

Topics (ranked):
1. `NewTopic` / `TopicBuilder` beans. `KafkaAdmin` creates them at startup. Partition count lives in code, versioned, reproducible. Recommended for app topics and the DLT (`-dlt`, same partition count as source; see Q4).
2. CLI in container. For ad hoc and learning. Good for inspection, not as source of truth.
3. Auto-create. Avoid: default partitions and RF are implicit.

CLI (run as `docker compose exec kafka /opt/kafka/bin/<tool>` [U path]):

```bash
# create (CLI route)
kafka-topics.sh --bootstrap-server localhost:9092 --create --topic payment-events --partitions 6 --replication-factor 1
# inspect
kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic payment-events
kafka-topics.sh --bootstrap-server localhost:9092 --list
# read records with key and offset
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic payment-events --from-beginning \
  --property print.key=true --property print.offset=true --property print.partition=true
# consumer groups, offsets, lag
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <group-id>   # CURRENT-OFFSET, LOG-END-OFFSET, LAG
# reset (dry-run first; group must be inactive)
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group <group-id> --topic payment-events --reset-offsets --to-earliest --dry-run
```

- Kafka 4 removed ZooKeeper tooling; these tools use the bootstrap server. [U] for exact flags on 4.x; check `--help`.

## Q3. Integration tests (Testcontainers 2.x)

- Artifact: `org.testcontainers:testcontainers-kafka:2.0.1`, test scope [V, testcontainers.com/modules/kafka]. Naming matches existing `testcontainers-postgresql`.
- Contradiction: a search snippet listed `org.testcontainers:kafka` (1.21.x line). Treat that as the legacy 1.x artifact. Confirm on Maven Central before adding.
- Container class: `org.testcontainers.kafka.KafkaContainer` [S]. Legacy `org.testcontainers.containers.KafkaContainer` is deprecated [S].
- Image: `apache/kafka-native:<tag>` [V example uses `3.8.0`; OpenRewrite recipe migrates to `4.0.2` [S]]. Contradiction on tag. Use the same tag as the compose file so tests match the broker learners run.
- KRaft: default in the Java module [S]. The fetched page did not state it [U].
- `@ServiceConnection`: Boot's Kafka connection-details factory matches `KafkaContainer` (and `RedpandaContainer`; Boot 3.4 docs add `ConfluentKafkaContainer`) [S, Boot testcontainers reference]. Verified on 3.x docs, not on 4.1. For the new `org.testcontainers.kafka` package on Boot 4.1: [U].
- Requires `spring-boot-testcontainers` on test classpath (already in project).
- Recommended test base (adds to `AbstractIntegrationTest`):

```java
@Container
@ServiceConnection
static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:<same-tag-as-compose>"));
```

- Fallback if `@ServiceConnection` does not bind on 4.1 [U]:

```java
@DynamicPropertySource
static void kafkaProps(DynamicPropertyRegistry r) {
    r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
}
```

- Async assertion without sleep: Awaitility, used via `spring-boot-starter-test` [V, Boot 4.0 test-scope page]. Transitive via split test starters [U].

```java
await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
    assertThat(paymentRepo.findById(id)).isPresent().get().extracting(Payment::getStatus).isEqualTo(SETTLED));
```

- Alternatives: `@EmbeddedKafka` from `spring-kafka-test` [V]. Faster but diverges from the Postgres Testcontainers setup. Not recommended here.

## Q4. Consumer semantics

| Topic | Default | Config / class | Source |
|---|---|---|---|
| Offset commit mode (AckMode) | `BATCH` | `spring.kafka.listener.ack-mode` (key [S]) | [V] container page: default BATCH, framework sets `enable.auto.commit=false` |
| Auto-commit | off (framework sets it) | `enable.auto.commit=false` | [V] |
| Listener exception default | Retry 9 times, no delay (10 attempts total), then log and skip | `FixedBackOff(0L, 9)` default in `DefaultErrorHandler` | [V] error-handling page |
| After retries exhausted | Logs failed record, continues. No DLT by default. | configure recoverer | [V] |
| Offset after handler | `isAckAfterHandle()` = true: offset committed if handler returns normally | `DefaultErrorHandler#setAckAfterHandle` | [V] |
| Non-retryable by default | `DeserializationException`, `MessageConversionException`, `ConversionException`, `MethodArgumentResolutionException`, `NoSuchMethodException`, `ClassCastException` | `addNotRetryableExceptions(...)` | [V] |
| Error subclasses (`Error`) | Bypass handler, consumer terminates | - | [V] |

Recommended handler for increment 3 (ranked):
1. Blocking retry + DLT. `DefaultErrorHandler(DeadLetterPublishingRecoverer, FixedBackOff(1000L, 3L))` or `ExponentialBackOffWithMaxRetries`. Keeps per-key order. Retries block the partition, which is acceptable at learning scale.
2. `@RetryableTopic` (non-blocking). Spreads retries across `-retry-<delay>` topics. Breaks per-payment order: a later record for the same key can overtake a retried earlier one. Use only if throughput beats order. Retry attempt and backoff defaults [U, from memory].
3. No retry, DLT on first failure. Simple, but transient DB failures (optimistic-lock conflicts, already seen in increment 2) go straight to DLT.

```java
@Bean
CommonErrorHandler errorHandler(KafkaTemplate<Object, Object> template) {
    var recoverer = new DeadLetterPublishingRecoverer(template);   // default: <topic>-dlt, same partition
    var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    handler.addNotRetryableExceptions(IllegalArgumentException.class);
    return handler;
}
```

- `DeadLetterPublishingRecoverer` defaults [V]:
  - Destination `<originalTopic>-dlt`.
  - Same partition as source. The DLT must have at least as many partitions as the source topic (create via `NewTopic`).
  - `verifyPartition` default true since 2.7: missing partition falls back to Kafka choosing one [V].
  - Headers kept, plus `DLT_EXCEPTION_FQCN`, `DLT_EXCEPTION_MESSAGE`, `DLT_EXCEPTION_STACKTRACE`, `DLT_ORIGINAL_TOPIC`, `DLT_ORIGINAL_PARTITION`, `DLT_ORIGINAL_OFFSET` [V].
- Rebalance redelivery [U on exact defaults, from memory]:
  - At-least-once. Uncommitted records are redelivered to the new owner.
  - Slow handler exceeding `max.poll.interval.ms` (Kafka default 300000 ms) triggers rebalance. Property: `spring.kafka.consumer.properties.max.poll.interval.ms`.
  - Lower `max.poll.records` (default 500) if handlers are slow: `spring.kafka.consumer.max-poll-records` [U on Boot key].
  - Mitigation that matters most: idempotent handler (DB unique key on event id or payment id + event type).
- Per-payment ordering:
  - Default partitioner hashes the record key (murmur2) to a partition [U on exact hasher; well known]. Same key = same partition = in-order consumption by one consumer.
  - Key = `paymentId`. Concurrency (`spring.kafka.listener.concurrency`) gives one thread per partition subset; order holds per key.
  - Order is lost by: retry topics, DLT re-processing, producer retries without idempotence, multiple producers writing same key without a sequence.

## Q5. Producer semantics

- `acks`: Kafka 3.0+ default `acks=all` with idempotence [U, from memory]. Spring Boot key `spring.kafka.producer.acks`.
- Idempotent producer: Kafka 3.0+ default `enable.idempotence=true` [U, from memory]. Spring key `spring.kafka.producer.properties.enable.idempotence`.
- With single node RF=1, `acks=all` only guarantees the single broker's log. Durability on laptop is not a real test.
- `KafkaTemplate.send(...)` returns `CompletableFuture<SendResult<K,V>>` [U on exact type for 4.x; long-standing]. Durable (as far as `acks` promises) when the future completes normally. Completion callback is async. Do not treat "send returned" as durable.
- DB and Kafka are not atomic:
  - Send after commit, crash in between: event lost.
  - Send before commit, commit fails: phantom event for a payment that does not exist.
  - Send inside tx does not roll back the Kafka side.
- Options (ranked):
  1. Transactional outbox: write event row in same DB tx as payment update; relay publishes and marks sent. At-least-once; consumers idempotent. Fits Postgres-backed increment 2. Adds one table + poller.
  2. `@TransactionalEventListener(phase = AFTER_COMMIT)` then `send`. Simple. Lossy on crash between commit and send.
  3. Kafka transactions with DB tx via `ChainedKafkaTransactionManager`: avoid. Kafka transactions remain for Kafka-to-Kafka consume-transform-produce [U]. Chained DB+Kafka: deprecated/removed status in 4.x [U, not verified; treat as do-not-use].

## Q6. Observability for the load test

- Boot Actuator exposure [U on exact keys for Boot 4.1]:

```properties
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.metrics.tags.application=gateway       # optional
```

- Prometheus endpoint needs a Micrometer Prometheus registry on classpath [U on Boot 4 starter name; expected `micrometer-registry-prometheus` via existing starter-actuator setup].
- Spring Kafka metrics via Micrometer [U, from memory]:
  - Boot auto-config binds Micrometer listeners to producer/consumer factories when a `MeterRegistry` exists.
  - Client metrics via kafka-clients: consumer `records-lag-max`, `records-consumed-rate`, producer `record-send-rate`, `request-latency-avg`. Micrometer names are dotted (`kafka.consumer.*`, `kafka.producer.*`) [U exact names].
  - Listener timing: `spring.kafka.listener` observations/timer [U; property `spring.kafka.listener.observation-enabled` from memory].
- Lag for the load test: use `kafka-consumer-groups.sh --describe` (LAG column) [U on command] as source of truth. Micrometer lag gauge is secondary and may be absent if no fetch metrics for that partition.
- Confidence: low on exact Micrometer metric names. Verify by hitting `/actuator/metrics` after first run.

---

## Source credibility
- Official, fetched: Spring Boot 4.1 Kafka page (via `docs.spring.io/spring-boot/reference/messaging/kafka.html`, body partly rendered), Spring Kafka container/error-handling pages, Spring Boot 4.0 test-scope page, Testcontainers Kafka module page, Spring Kafka reference index (version list only).
- Secondary: Spring blog (Jackson 3, 2025-10-07), stevenpg.com migration guide, Docker Kafka guide, Boot testcontainers reference (3.x), GitHub issue on Boot 4.0.2 starter (user report).
- Non-official: Tessl, OpenRewrite recipes, blog posts. Used only for pointers.
- Memory-only (no source): Kafka 3.0+ idempotence/acks defaults, `max.poll.interval.ms`, `CLUSTER_ID`-style compose tails, Micrometer metric names.

## Adoption risk
- Spring Kafka 4.x and Boot 4.1: active, current. Low abandonment risk.
- Boot 4 breaks: starter split, Jackson 3 default. Kafka serializer names changed in Jackson layer. Moderate upgrade risk for copied tutorials (Boot 3 `spring-kafka` + `JsonSerializer` examples will not match).
- Testcontainers 2.x: artifact-name churn (1.x `kafka` vs 2.x `testcontainers-kafka`). Pin and verify.
- Bitnami images deprecated trends: not checked, not used.

## Architectural fit
- Single laptop, compose: KRaft single node with two listeners is the only moving part. No ZooKeeper, no extra UI container needed.
- Learner at zero Kafka: blocking retry + DLT and BATCH ack keep the mental model small. Non-blocking retry and Kafka transactions add concepts the increment cannot teach well.
- Postgres outbox fits existing persistence; no new infra.
- Test parity: Testcontainers Kafka with the same image tag as compose avoids "works in tests, fails in compose" drift.

## Limitations (not covered)
- Did not open the Boot 4.1 Kafka page's property tables, the Spring Kafka 4.1 `ooo-commits`, `retrytopic`, or `transactions` pages, or the Apache producer/consumer config docs. Those are the main gap. Items tagged `[U]` depend on these.
- Did not open Apache's `docker/examples` compose files directly; env block above is reconstructed.
- No check of Kafka client version managed by Boot 4.1.2 (Dependency Versions appendix).
- No check of Micrometer metric names on Boot 4.1.
- Did not test any of this in the repo. Nothing was run.

## Unresolved questions
1. Exact `spring-boot-starter-kafka` and `spring-boot-starter-kafka-test` coordinates on the Boot 4.1 BOM. Does the test starter bring Awaitility?
2. Exact `apache/kafka` tag to pin (4.x) and whether `apache/kafka-native` tag matches for Testcontainers.
3. Does Boot 4.1 auto-config match `org.testcontainers.kafka.KafkaContainer` for `@ServiceConnection`?
4. Is `org.testcontainers:testcontainers-kafka:2.0.1` the correct artifact (vs `kafka` 1.x line)? Confirm on Maven Central.
5. Is `JsonSerializer` still present or deprecated in Spring Kafka 4.1? (Plan should use `JacksonJsonSerializer`.)
6. Exact default `@RetryableTopic` attempt count and backoff, if non-blocking retry is ever considered.
7. Exact `spring.kafka.listener.ack-mode` key and `spring.kafka.consumer.max-poll-records` key on Boot 4.1.
8. Micrometer metric names for Kafka clients and listener timers on Boot 4.1.
9. Whether `ChainedKafkaTransactionManager` is removed or only deprecated in 4.x (short answer needed only if the outbox is rejected).

Status: DONE_WITH_CONCERNS
Summary: Report written with starter names, compose env, Testcontainers artifact, consumer and producer defaults, and ranked recommendations. Several exact names and defaults are `[U]` because the Boot 4.1 Kafka property tables and Spring Kafka 4.x detail pages did not render.
Concerns/Blockers: Source gaps listed above. Testcontainers artifact version and `@ServiceConnection` on Boot 4.1 need a Maven Central and Boot 4.1 check before the plan locks them in.
