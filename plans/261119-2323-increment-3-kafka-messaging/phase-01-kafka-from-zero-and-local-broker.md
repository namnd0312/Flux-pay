---
phase: 1
title: "Kafka From Zero and Local Broker"
status: pending
priority: P2
effort: "1 session"
dependencies: []
---

# Phase 1: Kafka From Zero and Local Broker

## Context Links
- Research: `./research/researcher-01-spring-kafka-on-boot-4-report.md` (Q1-Q3; many items `[U]`, resolved by the spikes below)
- Red team: `./reports/from-code-reviewer-to-planner-red-team-assumption-destroyer-plan-review-report.md` (findings 4, 5, 6)
- `gateway/pom.xml`, `gateway/src/test/java/com/fluxpay/gateway/AbstractIntegrationTest.java`, `gateway/src/main/resources/application.yml`, `gateway/src/test/resources/application-test.yml`

## Overview
Bring a single-node KRaft broker into the local stack and the test base, declare the first topic, fix the rules that keep asynchronous tests isolated, and teach the vocabulary (topic, partition, offset, key, consumer group, lag) by hand with the Kafka CLI. No business flow changes in this phase.

## Key Insights
- Boot 4 split auto-configuration into modules: plain `spring-kafka` may not activate `spring.kafka.*`; use the starter.
- `spring-boot-testcontainers-4.1.2.jar` contains no Kafka class (checked locally), so `@ServiceConnection` for Kafka depends on the `spring-boot-kafka` module: unverified until the spike.
- The test suite shares one Postgres, will share one broker, and already caches two Spring contexts (`WebhookPessimisticLockIT` has its own property). A `@KafkaListener` that starts in every context would let one test's record be processed by another context's beans. Isolation has to be designed now, before the first listener exists.
- Spring Kafka runs one consumer thread per listener unless told otherwise; that thread then serves every partition. Partition-level parallelism is a setting, not a default.

## Requirements
- Functional: `docker compose up` starts a broker reachable from the gateway container (`kafka:19092`) and from the host (`localhost:9092`); the gateway declares `fluxpay.webhook-events` (3 partitions, RF 1) at startup; every integration test gets a broker.
- Non-functional
  - The gateway still starts and answers `/actuator/health` when the broker is down.
  - Test isolation rules (binding for phases 2-4): listeners do not start in the default test context; tests drive processors and the relay by direct calls; only dedicated listener ITs turn listeners on, each with a unique consumer group; assertions count only rows the test created.

## Architecture
- New package `com.fluxpay.gateway.messaging`: `KafkaTopicsConfig` (`TopicBuilder` beans; partition count from `fluxpay.messaging.partitions`, default 3).
- `application.yml`: `spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`, String key/value (de)serializers, `consumer.auto-offset-reset: earliest`, producer `acks: all`, listener concurrency = `fluxpay.messaging.partitions` (one thread per partition).
- `fluxpay.messaging.listeners.enabled` (default true) bound to every `@KafkaListener`'s `autoStartup`; `fluxpay.messaging.group-suffix` (default empty) appended to every group id.
- `application-test.yml`: `listeners.enabled: false`, `group-suffix: -${random.uuid}`.
- Test base: one static `org.testcontainers.kafka.KafkaContainer` next to Postgres in `AbstractIntegrationTest`.
- Broker: auto topic creation disabled, so a missing topic fails loudly instead of appearing with the broker's default partition count; named volume for the log dir and a fixed `CLUSTER_ID`.

## Related Code Files
- Modify: `gateway/pom.xml` (add `spring-boot-starter-kafka`, test `spring-boot-starter-kafka-test`, `org.testcontainers:testcontainers-kafka`; no explicit versions)
- Modify: `gateway/src/main/resources/application.yml`, `gateway/src/test/resources/application-test.yml`, `gateway/src/test/java/com/fluxpay/gateway/AbstractIntegrationTest.java`
- Create: `gateway/src/main/java/com/fluxpay/gateway/messaging/KafkaTopicsConfig.java`
- Create: `gateway/src/test/java/com/fluxpay/gateway/messaging/KafkaRoundTripIT.java`
- Create: `docs/learning/14-kafka-from-zero.md`
- Create (local, git-ignored): `docker-compose.increment-2.yml` (copy of today's compose file); modify `.gitignore` (ignore it, as `docker-compose.increment-1.yml` already is)
- Modify (local, git-ignored): `docker-compose.yml`; (tracked) `README.md` run section
- Delete: none

## Implementation Steps
1. Branch `feat/kafka-messaging`; tag the starting commit `increment-2-done`. **Before editing it**, copy `docker-compose.yml` to `docker-compose.increment-2.yml` (and note the `.env` values in use): phase 5 needs it to rebuild the "before" stack, and the tag cannot hold a git-ignored file.
2. **Spike A (image + env):** pick the pinned `apache/kafka:4.x` tag from Docker Hub; copy env keys from Apache's `docker/examples/.../single-node/plaintext` compose file, not from memory. Two listeners: `PLAINTEXT://kafka:19092` (containers), `PLAINTEXT_HOST://localhost:9092` (host, published on `127.0.0.1` only), plus `CONTROLLER`. Healthcheck; gateway `depends_on: kafka: condition: service_healthy` (labs stop the broker after startup with `docker compose stop kafka`); gateway env `KAFKA_BOOTSTRAP_SERVERS: kafka:19092`; volume + fixed `CLUSTER_ID`; auto-create off.
3. Add the dependencies. **Spike B:** `./mvnw -pl gateway dependency:tree -Dincludes=org.awaitility,org.apache.kafka,org.testcontainers` to confirm Awaitility is on the test classpath (add `org.awaitility:awaitility` test scope if not) and that only Testcontainers 2.x artifacts resolve.
4. **Spike C:** add the Kafka container with `@ServiceConnection`; if the context does not pick up the bootstrap servers, fall back to `@DynamicPropertySource` setting `spring.kafka.bootstrap-servers`. Record which one works in the base-class Javadoc. Use the image tag from Spike A (`apache/kafka-native` variant if the JVM image slows `verify` noticeably).
5. Add `KafkaTopicsConfig` and the `spring.kafka` block. **Spike D:** start the gateway with the broker stopped. It must start. Record: is the topic created once the broker returns, or only at the next gateway start? Set the `KafkaAdmin` option that makes a missing broker non-fatal if needed and note the property.
6. **Spike E (listener defaults and context behaviour):** with a throwaway listener in a scratch test, confirm against the running app (a) default concurrency is 1 and the configured value gives one thread per partition, (b) a listener with `autoStartup=false` never joins its group, (c) what a cached, idle Spring context does with a running listener. Write the three answers into the base-class Javadoc; delete the scratch code.
7. `KafkaRoundTripIT`: send keyed records with `KafkaTemplate`, read them with a test-owned consumer (unique group); assert same key -> same partition and per-key order preserved. Awaitility, no sleeps.
8. Write note 14 around five CLI labs run by the learner (`docker compose exec kafka /opt/kafka/bin/...`): create/describe a topic; produce without a key vs with a key and watch partitions (the "failure": events of one payment spread over partitions lose their order); two console consumers in one group vs two groups; lag and `--reset-offsets --dry-run` replay; kill a consumer and watch the group rebalance.
9. `./mvnw verify`; `docker compose up --build` and run the labs once to confirm every command in the note works as written.

## Todo List
- [ ] Branch + tag + compose snapshot
- [ ] Spikes A-E resolved and recorded
- [ ] Broker in compose (paste block in README, compose file stays local)
- [ ] Dependencies, topic config, test base, listener switches
- [ ] `KafkaRoundTripIT` green
- [ ] Note 14 with labs verified by running them

## Success Criteria
- [ ] `./mvnw verify` green with the broker container in the test base; existing tests untouched; added `verify` time recorded.
- [ ] `kafka-topics.sh --describe --topic fluxpay.webhook-events` shows 3 partitions after gateway start.
- [ ] Gateway starts and reports health with the broker stopped.
- [ ] `docker-compose.increment-2.yml` exists and starts the increment 2 stack from a worktree at the tag.
- [ ] Every CLI command in note 14 was executed against the pinned image and its output pasted or summarized in the note.

## Risk Assessment
- Unverified names (env keys, `@ServiceConnection`, CLI paths, concurrency property): each has a spike; nothing unverified goes into the note.
- Test time grows (one more container per JVM): single static container, reused across ITs.
- Port 9092 clash with another local project: publish on `127.0.0.1` and make the host port overridable (`KAFKA_HOST_PORT`), as was done for Postgres 5433.
- A topic created later with a different partition count than the code expects: auto-create is off and Spike D records when `KafkaAdmin` creates it.

## Security Considerations
- PLAINTEXT, no auth: acceptable only because the broker is bound to localhost and the compose network. Anything that can reach it can produce records, so later phases treat a record as untrusted input. Say both in the note.

## Next Steps
- Phase 2 uses the topic, the test base and the isolation rules.
