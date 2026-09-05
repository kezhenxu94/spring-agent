# spring-agent-persistence-redis

> **Audience:** a developer choosing or extending a persistence backend. An operator selects it with
> `app.persistence.type=redis` (`PERSISTENCE_TYPE`).

The Redis implementation of core's repository contracts, over Spring Data Redis repositories
(`@RedisHash`).

## What it contributes

A `Redis*Repo` per contract in `core/dao/repo/`, behind `@ConditionalOnPersistenceBackend("redis")`.
Conversation memory is Spring AI's own here, as on jpa. Two fragment interfaces
(`ScheduledTaskPartialUpdate`, `PendingQuestionStatusUpdate`) carry the repository methods Spring Data
Redis cannot generate: each writes one property without touching the rest, and claiming an occurrence
has to do so conditionally — which is what makes a scheduled task safe across replicas.

## Redis has no query planner

An `@Indexed` field is **the definition of what can be filtered on**, not a tuning knob. A query this
backend has to answer needs its field indexed on the model in `core/dao/models/`, and adding a
repository method without one leaves this backend the only one that cannot serve it.

## What an operator has to get right

Use Redis 8 or Redis Stack **configured to keep what it is given** — `maxmemory-policy noeviction`,
plus AOF or RDB. These are the agent's records, not a cache: a Redis provisioned for caching will
quietly evict a stored credential or an unfired scheduled task.

## Adding a model or a query

Update all three backends, and put the assertion in `AbstractPersistenceBackendTest`;
`PersistenceRedisTest` runs it against this one.
