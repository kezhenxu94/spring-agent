# spring-agent-persistence-mongodb

> **Audience:** a developer choosing or extending a persistence backend. An operator selects it with
> `app.persistence.type=mongodb` (`PERSISTENCE_TYPE`).

The MongoDB implementation of core's repository contracts.

## What it contributes

A `Mongo*Repo` per contract in `core/dao/repo/`, behind `@ConditionalOnPersistenceBackend("mongodb")`,
plus runtime hints — and one thing the other backends do not have.

## `MongoChatMemoryRepo`, and why it exists

Conversation memory is Spring AI's own repository on jpa and redis. On MongoDB it is this module's,
for two reasons. Spring AI's orders a turn by a millisecond timestamp it stamps itself, and so
returns a turn **scrambled** — answer before question. And it drops every tool call and every tool
response, so a run that used a tool leaves a conversation with the reasoning removed. Read that
class before touching it: the storage shape is deliberately the one
[spring-projects/spring-ai#6895](https://github.com/spring-projects/spring-ai/pull/6895) proposes,
so that the class can be deleted when a release carrying it is picked up.

`chatMemoryPreservesTheOrderOfATurn` in `AbstractPersistenceBackendTest` is what caught the ordering,
and is why that shared test covers Spring AI's conversation-memory repository even though it is not
one of core's contracts: it is selected by the same switch. Tool calling cannot go there — jpa's
repository still drops it — so `ChatMemoryMongoTest` covers it, including reading documents written
before either field existed.

## Adding a model or a query

Update all three backends, and put a behaviour that must hold for every backend in
`AbstractPersistenceBackendTest` — `PersistenceMongoTest` runs it against this one, over a
Testcontainers MongoDB.
