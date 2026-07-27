---
title: Deployment
description: Package a generated Vertique application, build its container image, and understand what the application owns versus what the deployment platform owns at runtime.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Deployment

By the end of this page you will understand what `mvn -ntp package` actually produces, how a
generated application's own container image is built, and which responsibilities — configuration,
secrets, health reporting, and everything below the application process — belong to the application
versus the platform that runs it.

## Package the application

Working directory: `rest-app/`, the REST application generated in [Quickstart](quickstart.md).

```bash
mvn -ntp package
```

Expected result: `BUILD SUCCESS`, producing `target/rest-app-0.1.0-SNAPSHOT.jar`. That jar contains
only the application's own compiled classes and resources, plus its
`META-INF/services/dev.vertique.core.VertiqueComponentFactory` service-registration entry — no
bundled dependency classes, and no `Main-Class` manifest attribute:

```text
Manifest-Version: 1.0
Created-By: Maven JAR Plugin 3.4.1
Build-Jdk-Spec: 25
```

Running it directly confirms this:

```bash
java -jar target/rest-app-0.1.0-SNAPSHOT.jar
```

Expected result: `no main manifest attribute, in target/rest-app-0.1.0-SNAPSHOT.jar`. This is a
deliberate framework choice, not a packaging omission: Vertique applications package as an OCI
container image via Jib rather than as a maven-shade fat jar, so `mvn -ntp package` on its own never
needs to assemble a runnable, dependency-bundling artifact. See
[`vertique-launcher`'s module reference](../../vertique-launcher/src/main/resources/META-INF/vertique/module.md)
for the full rationale and for `dev.vertique.launcher.VertiqueApplication`, the framework-owned
entrypoint class every generated application runs through.

Two supported paths turn this packaged jar into a running application, and both name that same
entrypoint class rather than the jar itself: locally, `mvn -ntp exec:java` (used throughout
[Quickstart](quickstart.md) and [Persistence](persistence.md)) runs `exec-maven-plugin` configured
with a `mainClass` of `dev.vertique.launcher.VertiqueApplication`, assembling the full Maven runtime
classpath itself; for deployment, Jib assembles that same classpath as container image layers
instead. Neither path is something your own application code has to wire — both are set up once, by
the archetype's generated `pom.xml`, parented on `vertique-app-parent`.

## Build a container image

The generated project's own `README.md` documents the container-build command directly:

```text
Builds a local container image with Jib.

mvn -ntp jib:dockerBuild
```

Working directory: `rest-app/`, with a reachable Docker daemon (`jib:dockerBuild` loads the built
image into your local Docker daemon; the `jib:build` goal, not covered here, pushes to a registry
instead and needs no local daemon at all).

```bash
mvn -ntp jib:dockerBuild
```

Running it announces the target image name up front — `rest-app`, taken from the generated
`pom.xml`'s own `${project.artifactId}` — then builds the application's dependency, resource, and
class layers before fetching the declared base image:

```text
[INFO] Containerizing application to Docker daemon as rest-app...
[INFO] Getting manifest for base image eclipse-temurin:21-jre...
[INFO] Building dependencies layer...
[INFO] Building snapshot dependencies layer...
[INFO] Building resources layer...
[INFO] Building classes layer...
[INFO] Building jvm arg files layer...
```

A completed run reports `BUILD SUCCESS` and leaves an image named `rest-app` in your local Docker
daemon, ready for `docker run` — the plugin configuration below names no explicit tag, so Docker
applies its own default tag to the reference. The generated `pom.xml`'s own `jib-maven-plugin`
configuration is exactly this:

```xml
<plugin>
  <groupId>com.google.cloud.tools</groupId>
  <artifactId>jib-maven-plugin</artifactId>
  <configuration>
    <from>
      <image>eclipse-temurin:21-jre</image>
    </from>
    <to>
      <image>${project.artifactId}</image>
    </to>
    <container>
      <mainClass>dev.vertique.launcher.VertiqueApplication</mainClass>
      <!-- Run as a numeric non-root user rather than as root. -->
      <user>65532:65532</user>
    </container>
  </configuration>
</plugin>
```

The `mainClass` named here is the identical `dev.vertique.launcher.VertiqueApplication` entrypoint
`exec-maven-plugin` names for the local run above — the container runs the same generated code
through the same framework-owned launcher, not a separate build. Once the image is running, publish
its two ports and call the same endpoints [Quickstart](quickstart.md) already proves locally — its
`/hello` call and this page's own [Health and observability](#health-and-observability) endpoints
below — substituting whatever host ports you chose in `docker run`'s own `-p` mapping.

## Runtime configuration in a container

[Configuration](configuration.md) establishes the rule this container image does not change: each
*relative* configured directory — `config/` by default — resolves against the running process's own
current working directory, never against the compiled classpath, and no configuration file is
required at all for the application to start with framework defaults. The Jib configuration above
sets no `container.workingDirectory`, so it does not override that resolution rule for the
containerized process either; a config file placed in the packaged jar's own resources is not, on
its own, evidence that a `config/` directory exists next to the container's actual working
directory. Provide runtime configuration to a container the same way you would to any other
Vertique process: mount or otherwise place a `config/` directory (or set `VERTX_CONFIG_LOCATIONS`)
next to wherever the container's entrypoint actually runs from, following
[Configuration](configuration.md)'s resolution rule rather than assuming the packaged
`src/main/resources/config/application.json` is read automatically.

## Secrets boundary

Secret material belongs to the platform running the container, never to the image or the
application's own repository. The application declares *where* a secret comes from — a
`${key}` placeholder reference, or a `config.stores` entry — and the platform is responsible for
making the backing secret store reachable and populated at runtime; see
[Configuration](configuration.md#secret-providers) for the placeholder idiom itself. Vertique's four
secret providers are:

- [`vertique-config-vault` module reference](../../vertique-config/vertique-config-vault/src/main/resources/META-INF/vertique/module.md) — HashiCorp Vault (KV v2)
- [`vertique-config-aws-secrets` module reference](../../vertique-config/vertique-config-aws-secrets/src/main/resources/META-INF/vertique/module.md) — AWS Secrets Manager
- [`vertique-config-aws-ssm` module reference](../../vertique-config/vertique-config-aws-ssm/src/main/resources/META-INF/vertique/module.md) — AWS SSM Parameter Store
- [`vertique-config-azure-keyvault` module reference](../../vertique-config/vertique-config-azure-keyvault/src/main/resources/META-INF/vertique/module.md) — Azure Key Vault

None of these providers writes a resolved secret value back into a file on disk, and none of them
belongs in a container image layer: the credentials each provider itself needs to reach its backing
store (a Vault token, an AWS role, an Azure managed identity) are the platform's responsibility to
supply — typically through environment variables, a mounted credential file, or an ambient identity
the runtime environment already grants the container — never by baking a credential into the image.

## Health and observability

The application's management port serves the same two endpoints in a container as anywhere else,
mounted by `vertique-management`'s `ManagementVerticle` on a port separate from the main HTTP
server:

| Endpoint | Backing check set | `UP` response | `DOWN` response |
|---|---|---|---|
| `GET /health/live` | `@Liveness`-qualified `HealthCheck` set | `200` | `503` |
| `GET /health/ready` | `@Readiness`-qualified `HealthCheck` set | `200` | `503` |

[Quickstart](quickstart.md)'s own generated integration test already calls `/health/live` and
asserts an `UP` status; a container orchestrator (or any other platform-owned process supervisor)
points its own liveness and readiness probes at these same two paths on the management port instead
of re-implementing health logic. See
[`vertique-management`'s module reference](../../vertique-management/src/main/resources/META-INF/vertique/module.md)
for the full response shape, aggregation rules, and how to contribute an application-owned
`HealthCheck`.

Metrics and distributed tracing are both opt-in, added by naming a module in your own component —
neither is wired by a starter automatically:

- [`vertique-micrometer-core` module reference](../../vertique-micrometer/vertique-micrometer-core/src/main/resources/META-INF/vertique/module.md)
  — the backend-agnostic `MeterRegistry` binding; pair it with a backend such as
  `vertique-micrometer-registry-prometheus`.
- [`vertique-opentelemetry-core` module reference](../../vertique-opentelemetry/vertique-opentelemetry-core/src/main/resources/META-INF/vertique/module.md)
  — opt-in distributed tracing and trace–log correlation.

## Platform responsibilities

Everything below the application process is the deploying platform's responsibility, not the
framework's or the application's:

- **Process supervision and restart** — starting the container, restarting it on crash or failed
  health probe, and rolling out a new image version.
- **Scaling** — how many instances run and how traffic is distributed across them.
- **TLS termination** — Vertique's own HTTP and management servers serve plain HTTP; terminating
  TLS in front of them (a load balancer, ingress controller, or sidecar) is a platform decision.
- **Log shipping** — the application logs to its configured output; collecting, shipping, and
  retaining those logs is the platform's concern.
- **Network policy** — which callers can reach the HTTP port, and whether the management port is
  reachable at all from outside the platform's own probes.

## Learn more

- [`vertique-launcher` module reference](../../vertique-launcher/src/main/resources/META-INF/vertique/module.md)
  — `VertiqueApplication`, its startup and shutdown sequence, and why applications package via Jib.
- [`vertique-management` module reference](../../vertique-management/src/main/resources/META-INF/vertique/module.md)
  — the health-check server, its two endpoints, and the `HealthCheck` extension point.
- [`vertique-micrometer-core` module reference](../../vertique-micrometer/vertique-micrometer-core/src/main/resources/META-INF/vertique/module.md)
- [`vertique-opentelemetry-core` module reference](../../vertique-opentelemetry/vertique-opentelemetry-core/src/main/resources/META-INF/vertique/module.md)
- [`vertique-config-vault` module reference](../../vertique-config/vertique-config-vault/src/main/resources/META-INF/vertique/module.md)
- [`vertique-config-aws-secrets` module reference](../../vertique-config/vertique-config-aws-secrets/src/main/resources/META-INF/vertique/module.md)
- [`vertique-config-aws-ssm` module reference](../../vertique-config/vertique-config-aws-ssm/src/main/resources/META-INF/vertique/module.md)
- [`vertique-config-azure-keyvault` module reference](../../vertique-config/vertique-config-azure-keyvault/src/main/resources/META-INF/vertique/module.md)
- [Quickstart](quickstart.md)
- [Configuration](configuration.md)
- [Testing](testing.md)
- [Documentation overview](index.md)
