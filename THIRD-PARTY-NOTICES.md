<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Third-Party Notices

Vertique itself is licensed under the [European Union Public Licence v. 1.2](LICENSE). This
document lists the third-party libraries the framework's own build depends on — every
coordinate carrying an explicit version in `pom.xml`'s `<dependencyManagement>` — together with
each library's license, for developers auditing license compliance before shipping an
application built on Vertique.

This is a notice, not a warranty: licenses are recorded as published by each upstream project at
the time of writing. Applications that add their own dependencies must audit those separately,
and any transitive dependency pulled in only through a BOM import (Vert.x, Jackson, Micrometer,
OpenTelemetry, Prometheus, AWS SDK) should be checked against that BOM's own bill of materials if
finer-grained attribution is required.

## Runtime and compile-time dependencies

| Coordinate | Version | License | Notes |
| --- | --- | --- | --- |
| `io.vertx:vertx-dependencies` (BOM) | 5.1.6 | Apache-2.0 OR EPL-2.0 | Recipient's choice; Vert.x is dual-licensed |
| `io.lettuce:lettuce-core` | 7.7.0.RELEASE | MIT | Redis client, isolated to the topology-maintenance adapter |
| `com.google.dagger:dagger` | 2.60.1 | Apache-2.0 | Compile-time DI, incl. `dagger-compiler` annotation processor |
| `com.fasterxml.jackson:jackson-bom` | 2.22.2 | Apache-2.0 | |
| `io.micrometer:micrometer-bom` | 1.16.6 | Apache-2.0 | |
| `io.opentelemetry:opentelemetry-bom` | 1.65.0 | Apache-2.0 | |
| `io.opentelemetry.semconv:opentelemetry-semconv` | 1.43.0 | Apache-2.0 | |
| `io.prometheus:prometheus-metrics-bom` | 1.8.0 | Apache-2.0 | |
| `org.projectlombok:lombok` | 1.18.42 | MIT | `provided` scope; compile-time only |
| `io.swagger.core.v3:swagger-*-jakarta` | 2.2.44 | Apache-2.0 | annotations / core / jaxrs2 / maven-plugin |
| `jakarta.ws.rs:jakarta.ws.rs-api` | 4.0.0 | EPL-2.0 OR GPL-2.0-with-classpath-exception | Eclipse EE4J dual license |
| `jakarta.inject:jakarta.inject-api` | 2.0.1 | Apache-2.0 | |
| `jakarta.annotation:jakarta.annotation-api` | 2.1.1 | EPL-2.0 OR GPL-2.0-with-classpath-exception | Eclipse EE4J dual license |
| `jakarta.validation:jakarta.validation-api` | 3.1.1 | Apache-2.0 | |
| `org.hibernate.validator:hibernate-validator` | 9.1.0.Final | Apache-2.0 | |
| `org.glassfish.expressly:expressly` | 6.0.0 | EPL-2.0 OR GPL-2.0-with-classpath-exception | Eclipse EE4J dual license |
| `ch.qos.logback:logback-classic` / `logback-core` | 1.6.3 | EPL-1.0 OR LGPL-2.1 | Recipient's choice |
| `org.slf4j:slf4j-api` | 2.0.17 | MIT | |
| `com.github.ben-manes.caffeine:caffeine` | 3.2.4 | Apache-2.0 | |
| `com.bucket4j:bucket4j_jdk17-core` / `bucket4j_jdk17-vertx` | 8.19.0 | Apache-2.0 | Isolated to `vertique-rate-limit-core`/`-redis` |
| `org.flywaydb:flyway-core` / `flyway-database-postgresql` | 12.0.2 | Apache-2.0 | Community edition; Redgate's paid-tier features are license-key gated but do not change this artifact's license |
| `org.postgresql:postgresql` | 42.7.13 | BSD-2-Clause | |
| `org.apache.avro:avro` | 1.12.1 | Apache-2.0 | |
| `io.apicurio:apicurio-registry-avro-serde-kafka` | 3.3.3 | Apache-2.0 | |
| `com.github.victools:jsonschema-generator` (+ `-module-jackson`, `-module-jakarta-validation`, `-module-swagger-2`) | 4.38.0 | Apache-2.0 | |
| `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer` | 20260313.1 | Apache-2.0 | Date-stamped version scheme |
| `dev.cel:cel` | 0.14.0 | Apache-2.0 | Isolated to `vertique-workflow-definition` |
| `io.github.jopenlibs:vault-java-driver` | 6.2.2 | MIT | Isolated to `vertique-config-vault` |
| `software.amazon.awssdk:bom` | 2.54.14 | Apache-2.0 | Isolated to `vertique-config-aws-*` |
| `com.azure:azure-security-keyvault-secrets` | 4.11.0 | MIT | Isolated to `vertique-config-azure-keyvault` |
| `com.azure:azure-identity` | 1.18.3 | MIT | Isolated to `vertique-config-azure-keyvault` |
| `com.palantir.javapoet:javapoet` | 0.19.0 | Apache-2.0 | Codegen source generation |
| `org.opentest4j:opentest4j` | 1.3.0 | Apache-2.0 | |

## Test-scoped dependencies

Not shipped with any published artifact, but declared centrally and worth the same license
review since test code, fixtures, and CI images redistribute them.

| Coordinate | Version | License |
| --- | --- | --- |
| `org.junit.jupiter:junit-jupiter` (+ `-api`) | 5.14.4 | EPL-2.0 |
| `io.rest-assured:rest-assured` | 6.0.0 | Apache-2.0 |
| `com.atlassian.oai:swagger-request-validator-restassured` | 2.46.0 | Apache-2.0 |
| `org.mockito:mockito-core` / `mockito-junit-jupiter` | 5.23.0 | MIT |
| `org.testcontainers:testcontainers` (+ `-postgresql`, `-kafka`, `-vault`, `-localstack`, `-junit-jupiter`) | 2.0.5 | MIT |
| `org.wiremock:wiremock-standalone` | 3.13.2 | Apache-2.0 |
| `com.github.nagyesta.lowkey-vault:lowkey-vault-testcontainers` / `lowkey-vault-client` | 7.3.0 | MIT |
| `com.google.testing.compile:compile-testing` | 0.23.0 | Apache-2.0 |
| `com.google.guava:guava` | 33.6.0-jre | Apache-2.0 |

## Build-only plugins

Not distributed with any Vertique artifact; listed for completeness since they run against this
source tree.

| Coordinate | Version | License |
| --- | --- | --- |
| `org.apache.maven.plugins:maven-compiler-plugin` | 3.15.0 | Apache-2.0 |
| `org.apache.maven.plugins:maven-dependency-plugin` | 3.11.0 | Apache-2.0 |
| `org.apache.maven.plugins:maven-surefire-plugin` | 3.6.0 | Apache-2.0 |
| `org.apache.maven.plugins:maven-failsafe-plugin` | 3.5.5 | Apache-2.0 |
| `org.apache.maven.plugins:maven-enforcer-plugin` | 3.6.3 | Apache-2.0 |
| `org.apache.maven.plugins:maven-shade-plugin` | 3.6.1 | Apache-2.0 |
| `org.apache.maven.plugins:maven-source-plugin` | 3.3.1 | Apache-2.0 |
| `org.apache.maven.plugins:maven-javadoc-plugin` | 3.12.0 | Apache-2.0 |
| `org.apache.maven.plugins:maven-deploy-plugin` | 3.1.4 | Apache-2.0 |
| `org.codehaus.mojo:exec-maven-plugin` | 3.6.3 | Apache-2.0 |
| `com.google.cloud.tools:jib-maven-plugin` | 3.5.1 | Apache-2.0 |
| `com.diffplug.spotless:spotless-maven-plugin` | 3.10.2 | Apache-2.0 |
| `org.jacoco:jacoco-maven-plugin` | 0.8.12 | EPL-2.0 |
| `org.codehaus.mojo:flatten-maven-plugin` | 1.8.0 | Apache-2.0 |

## Known compliance flags

- **`com.google.guava:guava` is explicitly managed at 33.6.0-jre**, matching the version
  `com.google.dagger:dagger-compiler` 2.60.1 itself declares. Without the override, Maven's
  mediation picks the older 32.1.2-jre that `com.google.testing.compile:compile-testing` pulls
  transitively, and dagger-compiler's codegen fails at runtime with `NoSuchMethodError` on a
  `Graphs` API method that older release lacks.
- **`org.apache.maven.plugins:maven-failsafe-plugin` stays pinned at 3.5.5** (latest is 3.6.0).
  3.6.0 regresses skip-property handling for the archetype modules' custom
  `post-integration-test`-bound executions: with `archetype:integration-test` skipped via
  `-Darchetype.test.skip=true`, `-DskipTests` no longer suppresses the dependent reproducibility
  check, which then fails on the generated-project directory it expected `archetype:integration-test`
  to have produced. Confirmed by isolating the bump against a clean build. Revisit once upstream
  addresses it.
- **Classpath-exception artifacts** — `jakarta.ws.rs-api`, `jakarta.annotation-api`, and
  `expressly` carry GPL-2.0-with-classpath-exception as their secondary license option. The
  classpath exception permits linking without extending GPL terms to Vertique or applications
  built on it; Vertique consumes the EPL-2.0 option in practice.
- **`org.flywaydb:flyway-core`** — Redgate license-gates several Flyway features (e.g.
  undo/dry-run migrations) behind a commercial tier starting from a paid edition; the
  `flyway-core` and `flyway-database-postgresql` artifacts Vertique depends on remain
  Apache-2.0-licensed open source.

## Maintaining this list

Coordinates and versions here are sourced directly from `pom.xml`'s `<dependencyManagement>`.
When that block changes, refresh the corresponding row(s) — new dependency, version bump, or
removal — in the same change. License identifiers rarely change for an existing dependency, but
re-verify before merge if a major version bump is involved.
