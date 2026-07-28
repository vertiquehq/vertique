<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Developing Vertique Services

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `src/main/resources/META-INF/vertique/module.md`

Read the public contract first. This document explains how `vertique-services` implements that
contract and where a framework change must preserve parity. It is source-only: application usage,
configuration reference, and extension recipes remain in the packaged module document.

---

## Source Map

| Area | Responsibility |
|---|---|
| `dev.vertique.services` | Contract discovery and validation, registry assembly, addressing, client transport, deployment, supervision, lifecycle, and Dagger wiring |
| `dev.vertique.services.config` | Typed parsing and validation of the `services` configuration tree |
| `dev.vertique.services.dispatch` | Immutable operation metadata, reflective method resolution, invocation, dispatch scopes, and result handling |
| `dev.vertique.services.interceptor` | Ordered dispatch SPI plus built-in authorization and identity-degradation gates |
| `dev.vertique.services.policy` | Resolution of annotation/config resilience values into the Vert.x circuit-breaker pipeline |
| `vertique-codegen/vertique-codegen-services` | Compile-time contract validation and generated `ServiceContractContributor`/Dagger module emission |

The root package contains both public integration types and internal collaborators. Java visibility
alone is not the documentation boundary: a type belongs in the packaged reference only when an
application must call, implement, configure, or deliberately replace it.

---

## Registration and Deployment Flow

```text
application implementation
        │
        ├─ generated ServiceContractContributor (normal path)
        └─ @Services object / hand-written contributor (explicit fallback)
                        │
                        ▼
             ServiceContractRegistry
                        │
             global identity/collision checks
                        │
                        ▼
             ServiceDeploymentManager
                        │
              one ServiceVerticle per contract
                        │
              one event-bus consumer per operation
```

`DispatchModule` is the composition root:

1. Dagger collects generated/manual `ServiceContractContributor` entries and `@Services` objects.
2. `ServiceContractRegistry` merges them into one immutable registry.
3. Address and stable-target collisions are validated across every source, not per contributor.
4. `ServiceDeploymentStartupStep` asks `ServiceDeploymentManager` to deploy all entries in the
   `SERVICES` lifecycle phase.
5. Each `ServiceVerticle` builds the operation policy pipeline once and registers a
   `ServiceMethodInvoker` consumer.
6. `ServiceDeploymentShutdownStep` undeploys the same managed deployments.

Do not add a second startup path. Service deployment ordering belongs to the application lifecycle,
and direct deployment calls would bypass its rollback and shutdown ownership.

---

## Generated and Reflective Boundaries

The compile-time processor is the normal registration path. It validates the contract/handler shape
and emits one contributor per contract group plus one `GeneratedServicesModule` for the compilation
unit. The runtime module still accepts hand-written contributors and reflective `@Services` entries
for explicit integration and fallback cases.

Generated and runtime paths must remain behaviorally equivalent for:

- operation-name and stable-id resolution;
- payload versus dispatch-context classification;
- handler-method matching and generic return types;
- direct versus handler implementation rules;
- resilience, method, and class annotation metadata;
- one-way semantics;
- deployment configuration; and
- address/stable-target collision validation.

When any of those rules changes, inspect and update both the runtime collaborator and its APT-side
counterpart. The processor tests intentionally mirror runtime tests; adding proof to only one side
leaves a release-time parity gap.

Service clients currently use `ServiceClientFactory` and a JDK dynamic proxy. The factory owns proxy
construction and argument extraction; `ServiceRequestSender` owns availability checks, timeout
selection, request/send transport, and service-specific exception enrichment. Keep that ownership
seam when changing or replacing client generation: metadata/envelope construction must not absorb
transport lifecycle policy.

---

## Dispatch Flow

For each incoming event-bus message, `ServiceMethodInvoker`:

1. installs an `InboundDispatchScope` by decoding every registered typed dispatch-context value;
2. runs ordered `beforeDispatch` handlers, where a failure short-circuits invocation;
3. invokes the handler through the prebuilt resilience pipeline, or directly when no policy applies;
4. translates handler failure through `ServiceExceptionMapper`;
5. calls `onComplete` with the pre-recovery handler result;
6. runs independent `afterDispatch` work;
7. offers recoverable failures to `recoverError` in extension order;
8. calls `onTerminalComplete` once with the final post-recovery result while the original Vert.x
   context and dispatch scope are still active;
9. replies with `Result<?>`, or logs a one-way failure without replying; and
10. closes the inbound scope on every terminal path.

The pre-recovery and terminal observer hooks are different contracts. Do not collapse them:
handler-level metrics and terminal audit evidence need different outcomes.

`VirtualMachineError` is never translated or wrapped. Authorization denials and snapshot-degradation
failures implement `NonRecoverableDispatchFailure` and bypass application recovery.

---

## Load-Bearing Invariants

### Contract identity

- Runtime addresses use slash-delimited segments; durable target ids use dot-delimited segments.
- Only an explicit `@ServiceOperation` creates a stable operation id.
- Address and stable-target uniqueness is global across annotations and contributors.
- Persisted integrations store the stable id and resolve the current address at dispatch time.

### Method shape

- Contract and handler method overloads are rejected because handler matching is name-based.
- Operations return parameterized `Future<T>`; `@OneWay` narrows that to `Future<Void>`.
- There is at most one payload parameter.
- `DispatchEnvelope<?>` never appears in an application contract.
- Handler-only context parameters are looked up by their registered type key.
- Registration collects violations so a user sees the complete invalid contract rather than one
  error per build/run cycle.

### Context and thread affinity

- `DispatchEnvelopeBuilder` is the single outbound capture boundary.
- `InboundDispatchScope` owns restoration and must close in every success, failure, recovery, and
  one-way path.
- Terminal observation runs on the original dispatch Vert.x context while that scope is open.
- Ambient `SecurityContext` wins over an explicit legacy contract parameter, preventing duplicate
  dispatch-context keys.

### Resilience

- Policy metadata is resolved once during registration/deployment, not per invocation.
- `@Timeout` overrides `@CircuitBreaker.timeoutMs`.
- Timeout applies per retry attempt, not to the whole call budget.
- `abortOn` has priority over `retryOn`.
- No annotations means no pipeline allocation for the operation.

### Supervision and health

- Client dispatch checks `ServiceSupervisor` availability before transport.
- Restart thresholds and backoff come from the typed service configuration.
- Readiness reflects supervisor state through `ServiceSupervisorHealthCheck`.
- Deployment and supervisor bookkeeping must agree before startup or shutdown completes.

---

## Extension Ordering and Built-In Gates

`ServiceInterceptor` and `ServiceExceptionMapperCustomizer` use the framework-wide
`OrderedExtension` comparator: extension phase, ascending numeric priority, then stable
`orderKey`.

Built-in security gates occupy `SYSTEM_FIRST`:

- `SnapshotDegradationGate` runs before authorization so unverifiable reconstructed identity can
  fail before any action decision.
- `ServiceAuthorizationInterceptor` validates and enforces `@RequiresAction`.

The degradation gate emits its security event before applying the configured verdict. The
authorization interceptor owns emission of its decision event. Do not move either emission to a
shared observer: enforcement and evidence must remain in the same layer.

Exception mapper customizers are applied as an ordered last-wins fold. Later registrations replace
earlier translations for the same exception type.

---

## Testing

Use focused tests while editing:

| Concern | Primary tests |
|---|---|
| discovery, validation, and registry | `ServiceRegistrarTest`, `ServiceHandlerRegistrarTest`, `ServiceContractRegistryTest`, `ServiceContractEntriesTest` |
| generated/runtime parity | `ServiceContractProcessor*Test` in `vertique-codegen-services` plus the matching registrar tests |
| client and transport failures | `ServiceClientFactoryTest`, `ServiceRequestSenderTest`, service exception tests |
| invocation and context lifetime | `ServiceMethodInvokerTest`, `ServiceMethodInvokerCharacterizationTest`, `ServiceHandlerInvokerTest`, propagation tests |
| interceptor ordering and security | `ServiceInterceptorOrderTest`, `ServiceAuthorizationInterceptorTest`, `ServiceAuthorizationInterceptorIT`, `SnapshotDegradationGateTest`, `IdentitySnapshotRoundTripTest` |
| resilience | `PolicyChainBuilderTest`, `DispatchPipelineTest` |
| deployment and supervision | `ServiceDeploymentManagerTest`, lifecycle step tests, `ServiceSupervisorTest`, `ServiceSupervisorHealthCheckTest` |
| typed configuration | `ServicesConfigTest` |

Focused module verification from the public repository root:

```bash
./mvnw -ntp -pl vertique-services,vertique-codegen/vertique-codegen-services -am test
```

Before handoff, run the full required verification:

```bash
./mvnw -ntp clean verify
```

When changing generated source shape, also inspect a generated fixture or the services-codegen
example instead of relying only on string assertions.

---

## Related ADRs

- ADR-0084: Framework Extension-Ordering Contract — phase dominates priority and `orderKey` breaks
  ties deterministically.
- ADR-0085: OrderedExtension Rollout — applies that ordering contract to service interceptors and
  exception-mapper customizers.
- ADR-0104: Typed Configuration from Keyed Objects — governs parsing of
  `services.contracts.{namespace}.{name}` into validated records.
- ADR-0108: Unified Failure Mapping — keeps service translation on the shared context-aware
  `FailureMapper` model.
- ADR-0113: Federated Action and Policy Authorship — allows service contracts to author
  `@RequiresAction` gates without a central action registry file.
- ADR-0114: Enforcement-Layer Emission Ownership — requires the service authorization layer to emit
  its own decision evidence.
