# ${artifactId}

A PostgreSQL-backed Vertique REST application exposing item CRUD under `/items`.

## Prerequisites

- JDK 21
- Apache Maven
- A reachable Docker daemon, required by the integration test's PostgreSQL container

## Database

`src/main/resources/config/application.json` points at a local PostgreSQL instance and applies the
application-owned migrations in `src/main/resources/db/migration` at startup
(`flyway.mode=MIGRATE`). Its connection values are development-only placeholders and are not
suitable for production.
