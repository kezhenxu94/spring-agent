# spring-agent-persistence-jpa

> **Audience:** a developer choosing or extending a persistence backend. An operator selects it with
> `app.persistence.type=jpa` (`PERSISTENCE_TYPE`), which is the default.

The JPA implementation of core's repository contracts, on SQLite by default. It is the backend that
needs nothing running alongside it, which is why the command line and every application's default use
it.

## What it contributes

A `Jpa*Repo` per contract in `core/dao/repo/`, all behind `@ConditionalOnPersistenceBackend("jpa")`,
plus runtime hints. Conversation memory is **Spring AI's own** JDBC repository here, selected by the
same switch — this module does not implement one.

Schema is owned by the application (`ddl-auto: update`, `JPA_DDL_AUTO`). There is no Flyway or
Liquibase.

## Adding a model or a query

Do it in all three backends. The domain records in `core/dao/models/` carry JPA, MongoDB *and* Redis
mapping annotations at once — that works because an annotation whose type is absent at runtime is
discarded on reflection, which is also why core declares those persistence APIs `compileOnly`.

A behaviour that must hold for every backend goes in `AbstractPersistenceBackendTest`, which
`PersistenceJpaTest` runs against this module. Add the assertion there rather than to one backend's
test.
