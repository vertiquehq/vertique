<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Rate Limit Core

> **Status:** Alpha
> **Package:** `dev.vertique.ratelimit`
> **Artifact:** `vertique-rate-limit-core`
> **Depends on:** `vertique-core`, `vertique-context`, `vertique-security-core`

`vertique-rate-limit-core` is the provider-neutral foundation for programmatic quota
admission. Injected `RateLimiters` resolves named policies into `RateLimiter`/
`KeyedRateLimiter<K>` handles; the in-process LOCAL engine is always available from
this artifact alone. Annotation support is layered in `vertique-rate-limit-aop`, a
shared-state CLUSTERED engine in `vertique-rate-limit-redis`, and telemetry in the
Micrometer/OpenTelemetry adapters — none of that is exposed here.

## When To Use It

Install `RateLimitCoreModule` whenever an application needs quota admission for at
least one policy. LOCAL mode needs no further module. Add
`vertique-rate-limit-redis` for any policy that must share state across instances,
`vertique-rate-limit-aop` (plus `vertique-codegen-rate-limit` on the processor path)
for the `@RateLimited` annotation, and `vertique-micrometer-rate-limit`/
`vertique-opentelemetry-rate-limit` for telemetry.

## Core Concepts

`RateLimiters` is the single injected entry point (one per application graph, no
static registry). It resolves a named policy into a cached handle immediately:
`limiter(String)` returns an untyped `RateLimiter`; `limiter(String, Function<? super
K, RateLimitKey>)` returns a `KeyedRateLimiter<K>` bound to one caller-supplied
key-selector function. An unknown policy name fails handle creation with
`IllegalArgumentException` — never at first `acquire`.

Both handle types are decision-first: `acquire(...)` never fails the returned future
for quota reasons, it always yields a `RateLimitDecision`. `execute(...)` is the
guarded wrapper — `QUOTA_EXCEEDED` fails with `RateLimitExceededException`,
`BACKEND_FAILURE_CLOSED` fails with `RateLimitUnavailableException`, and
`PERMITTED`/`BACKEND_FAILURE_OPEN`/`DISABLED` run the supplied action exactly once,
with no retry and no refund. A cost above the policy's capacity fails the future with
`RateLimitRequestException(COST_EXCEEDS_CAPACITY)` and is never subject to fail-open
behavior.

`RateLimiters` is force-constructed at bootstrap through an `ApplicationShutdownStep`
`@IntoSet` contribution, so its startup validation runs even for a policy nothing has
referenced yet: duplicate policy names, any enabled policy whose mode has no bound
backend, and a missing or under-length `keyDerivation.secret` when any enabled policy
is CLUSTERED all fail startup.

`RateLimitKey` follows the `CacheKey` idiom: `RateLimitKey.of(Object first, Object...
rest)` frames an ordered scalar-component tuple (String, Character, Boolean,
integral types, `BigInteger`/`BigDecimal`, finite `Float`/`Double`, `Enum`, `UUID`,
`java.time` scalars); `RateLimitKey.global()` is the explicit zero-component bucket.
Each framed component is bounded at 256 bytes UTF-8 post-encoding; an oversized
component is deterministically replaced by `h:` followed by the lowercase-hex
SHA-256 digest of its pre-encoding value — a fixed-width, distinctness-preserving
substitution, never a rejection path.

A policy is one `RateLimitPolicy`: `name`, `enabled`, `mode` (`LOCAL`/`CLUSTERED`),
`failureMode` (`OPEN`/`CLOSED`, no default for any policy), `revision`, `defaultCost`,
and one `RateLimitAlgorithm` — v1 ships `TokenBucketRateLimit(capacity, refill)` with
`GreedyRateLimitRefill`/`IntervalRateLimitRefill`. Configuration-declared policies
replace a same-named Dagger-contributed policy wholesale (fields never merge); a
semantic change needs a new `revision`. **Choose `failureMode` deliberately for
key-space saturation, not only for a genuine backend outage:** under `OPEN`, a LOCAL
registry at its configured `maxTrackedKeys` budget silently *admits* every request for
a not-yet-tracked key once saturated — an attacker who can mint high-cardinality keys
(e.g. an unauthenticated per-IP or per-header dimension) can exploit this to disable
the limiter's protection entirely; under `CLOSED` it denies instead. Either way,
monitor `vertique.ratelimit.failures{code="capacity-exhausted"}` and the `WARN` log
line below — this failure mode is silent otherwise.

LOCAL mode stores `Bucket` state in a bounded, **per-policy** concurrent registry:
each enabled policy owns its own registry sized by `rateLimit.local.maxTrackedKeys`
(or its own `rateLimit.policies.<name>.local.maxTrackedKeys` override), never one
shared pool across policies — `CAPACITY_EXHAUSTED` pressure on one policy's key
space can never surface as an outcome, latency change, or eviction on any other
policy. Inactive state is reclaimable only after the worst-case time to full plus a
configured retention slack; active state is never evicted merely to make room. The
registry's internal storage key is `policyName:policyRevision:canonicalKeyEncoding`
(the same canonical input `RateLimitKey` framing produces); LOCAL retains this raw key
verbatim, in-memory, for the process's lifetime — by design (D015: LOCAL never leaves
the process and needs no cross-instance unforgeability, so it carries no HMAC/hashing
cost CLUSTERED's physical key does). If a key component can itself be sensitive
(rarely — most policies key on tenant/user id or IP, not raw secrets), account for that
retention in your threat model; CLUSTERED never retains the raw key, only its HMAC
digest. The first `CAPACITY_EXHAUSTED` admission of a saturation episode also logs one
`WARN` — the policy name and configured budget only, never key material — so an
`OPEN`-mode silent-admit degradation and a `CLOSED`-mode denial spike both leave a
signal an operator can alert on; see the `failureMode` caution above.

A subject-resolution SPI (`RateLimitSubjectResolver`) and its shared framing helper
(`RateLimitAdapterSupport`) let the annotation, REST, and future adapters resolve an
identity-scoped `RateLimitKey` without hand-rolling identity encoding — see
Extension Points.

Every consumed decision reports one redacted, synchronous `RateLimitDecisionCompleted`
event to the optional `RateLimitObserver` set: policy name/revision/mode/algorithm,
outcome, cost, capacity, remaining, retry/reset durations, failure code, and backend
latency only — never a key, identity, IP, credential, header, or exception. Dispatch
is synchronous and per-observer exception-isolated; an implementor's only obligation
is to stay bounded and non-blocking, since observers run on the admission hot path.

## Key Classes

### RateLimiters

`limiter(String policyName)` and `<K> limiter(String policyName, Function<? super K,
RateLimitKey> keySelector)` resolve named policies into handles; `close()` is
idempotent and force-fails in-flight operations on a second call.

```java
RateLimiter search = rateLimiters.limiter("search-quota");
return search.acquire(RateLimitKey.of(tenantId, userId)).compose(decision -> {
    if (decision.permitted()) {
        return searchService.run(query);
    }
    if (decision.outcome() == RateLimitOutcome.QUOTA_EXCEEDED) {
        return Future.failedFuture(new SearchQuotaExceeded(decision));
    }
    return Future.failedFuture(new AdmissionUnavailable(decision));
});
```

### RateLimiter / KeyedRateLimiter\<K\>

`policyName()`, `failureMode()`, and (on `RateLimiter` only) `capacity()` expose the
resolved policy's identity and its token-bucket capacity. Both expose
`acquire(key[, cost])` and `execute(key[, cost], Supplier<Future<T>> action)`; the
keyed handle applies its bound selector function before delegating:

```java
KeyedRateLimiter<ReportRequest> reports =
        rateLimiters.limiter("report-quota", r -> RateLimitKey.of(r.tenantId()));
return reports.acquire(request).compose(decision ->
        decision.permitted() ? generate(request) : Future.succeededFuture(tooBusy(decision)));
```

### RateLimitDecision

`record RateLimitDecision(String policyName, RateLimitOutcome outcome, RateLimitMode
mode, RateLimitAlgorithmType algorithm, long capacity, OptionalLong remaining,
Optional<Duration> retryAfter, Optional<Duration> resetAfter,
Optional<RateLimitFailureCode> failureCode)`. `permitted()` is `true` for
`PERMITTED`, `BACKEND_FAILURE_OPEN`, and `DISABLED`. `retryAfter`/`resetAfter` are
relative durations (never an absolute instant, to avoid unreliable client-clock
comparison) and `retryAfter` is present and positive only when the request was
rejected.

### RateLimitKey

`RateLimitKey.of(Object first, Object... rest)` and `RateLimitKey.global()`; see Core
Concepts for the framing discipline. Distinct component tuples always produce
distinct canonical keys.

## Extension Points

`RateLimitSubjectResolver` is the one genuine application extension point:

```java
public interface RateLimitSubjectResolver {
    Optional<SecurityIdentity> current();
}
```

It is declared `@BindsOptionalOf`. The framework default reads the current
`SecurityContext` off the framework `ContextHolder`; supplying one custom
`RateLimitSubjectResolver` binding replaces it application-wide. `RateLimitAdapterSupport`
is the shared identity-framing seam the annotation and REST adapters use on top of the
resolved identity — it is integration surface for adapters, not application API to call
directly in ordinary business code.

## Module Dagger Bindings

| Binding | Kind | Description |
|---|---|---|
| `Map<RateLimitMode, RateLimitBackend>` | `@Multibinds` | Backend provider map; core contributes the `LOCAL` entry, `vertique-rate-limit-redis` contributes `CLUSTERED` |
| `Set<RateLimitPolicy>` | `@Multibinds` | Programmatic policy contributions merged under configuration-declared policies |
| `Set<RateLimitObserver>` | `@Multibinds` | Observer contributions from telemetry adapters or application code |
| `RateLimitSubjectResolver` | `@BindsOptionalOf` | See Extension Points |
| `RateLimiters` | `@Provides @Singleton` | The application entry point |
| `ApplicationShutdownStep` | `@Provides @IntoSet` | Forces eager `RateLimiters` construction so startup validation runs unconditionally |

The `LOCAL` backend entry itself is a framework-private Bucket4j implementation
reached only through this map; it is Dagger composition surface, not something an
application implements or replaces.

## Verification

Run the rate-limit family proof with:

```text
./mvnw -ntp -pl vertique-rate-limit/vertique-rate-limit-core,vertique-rate-limit/vertique-rate-limit-aop,vertique-rate-limit/vertique-rate-limit-redis,vertique-codegen/vertique-codegen-rate-limit,vertique-rest/vertique-rest-rate-limit,vertique-micrometer/vertique-micrometer-rate-limit,vertique-opentelemetry/vertique-opentelemetry-rate-limit -am verify
```

The clean reactor verification additionally checks dependency and BOM parity and
packaged module-documentation parity:

```text
./mvnw -ntp clean verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-core` | Framework foundations and shared configuration/runtime contracts |
| `vertique-context` | Vert.x context propagation for observer/decision dispatch |
| `vertique-security-core` | Provider-neutral `SecurityContext`/`SecurityIdentity` contracts for subject resolution |
