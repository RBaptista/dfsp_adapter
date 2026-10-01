# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Quarkus 3 (Java 21, Maven) service acting as a DFSP (Digital Financial Service Provider) adapter, modeling DFSPs, customers, accounts and parties (MSISDN-identified). Persistence is PostgreSQL via Hibernate ORM with Panache; schema is managed by Flyway. MapStruct maps between domain and JPA entities.

## Commands

```shell
./mvnw quarkus:dev                         # dev mode with live reload; Dev UI at http://localhost:8080/q/dev/
./mvnw test                                # unit/@QuarkusTest tests (surefire)
./mvnw test -Dtest=PersistenceRoundTripTest # single test class (append #method for one method)
./mvnw verify -DskipITs=false              # also run @QuarkusIntegrationTest (*IT) against the packaged app; ITs are skipped by default
./mvnw package                             # builds target/quarkus-app/quarkus-run.jar
./mvnw package -Dnative                    # native build (add -Dquarkus.native.container-build=true without GraalVM)
```

No linter/formatter is configured. Dockerfiles for JVM/native images are in `src/main/docker/`.

## Database

- `application.properties` points at a local PostgreSQL 16: `jdbc:postgresql://localhost:5432/dfsp` (user `postgres`). It must be running for dev mode and tests — there is no separate test/dev-services datasource config, so tests use the same DB.
- `quarkus.flyway.migrate-at-start=true` and `quarkus.hibernate-orm.schema-management.strategy=validate`: Hibernate never creates/alters tables. **Any entity change requires a new Flyway migration** in `src/main/resources/db/migration/` (`V<n>__description.sql`), or startup fails validation. Never edit an already-applied migration.
- Ids use Hibernate's default `@GeneratedValue`: one `<table>_seq` sequence per table with increment 50 — new tables need a matching sequence in the migration.
- `dfsp` is the exception: its PK is `fsp_id varchar(32)`, not a generated `bigint`.

## Architecture (hexagonal / ports & adapters)

All code lives under `cv.sitech`:

- `domain/` — plain POJOs (`Customer`, `Account`, `Party`, `Dfsp`, `Contact`, enums) and domain exceptions. No JPA/framework annotations here.
- `application/port/in/` — `*UseCase` interfaces (inbound ports) implemented by `application/service/*Service`. Services take outbound ports via constructor and put `@Transactional` on write methods; lookups throw `domain.exception.NotFoundException`.
- `application/port/out/` — `*Repository` interfaces (outbound ports) the services depend on.
- `adapter/out/entity/` — JPA `*Entity` classes (property/getter-based annotations), mirroring the domain types.
- `adapter/out/persistence/` — `*PanacheRepository` beans (`PanacheRepositoryBase<XEntity, ID>`), `PanacheAdapter<D, E, ID>` (generic base implementing save via `EntityManager.merge`/findById/findAll/deleteById from a Panache repository + `EntityMapper`), and one `XPersistenceAdapter` per aggregate that extends it and implements the `XRepository` port.
- `adapter/out/persistence/mapper/` — MapStruct `@Mapper` interfaces extending `EntityMapper<Domain, Entity>`; nested types are wired with `uses = ...`. The processor runs with `-Amapstruct.defaultComponentModel=jakarta-cdi` (set in `pom.xml`), so generated `*MapperImpl` classes are `@ApplicationScoped` beans — don't add `componentModel` per mapper.

Wiring for an aggregate: `XService` (`@ApplicationScoped`) → `XRepository` (port) ← `XPersistenceAdapter` → `XPanacheRepository` + `XMapper`.

Conventions that matter for MapStruct and CDI:

- Domain and entity property names must match exactly (e.g. `msisdn`, `initialPosition`). MapStruct matches domain constructor *parameter names* to properties, and maps the rest (like `id`) through setters.
- `XPersistenceAdapter` needs both a `protected` no-arg constructor (for ArC's client proxy, because `PanacheAdapter` has no no-arg constructor) and an `@Inject` constructor. Without `@Inject`, CDI picks the no-arg constructor and every field ends up `null`.
- Domain money fields are `Long`/`Double`/`Float` while columns are `numeric`/`real`; MapStruct converts them implicitly.

There are no inbound (REST) adapters yet. `PersistenceRoundTripTest` covers the persistence path end-to-end, using `@TestTransaction` so its writes are rolled back.
