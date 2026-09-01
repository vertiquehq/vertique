<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Rate Limit Redis

> **Status:** Alpha
> **Package:** `dev.vertique.ratelimit.redis`
> **Artifact:** `vertique-rate-limit-redis`
> **Depends on:** `vertique-rate-limit-core`, `vertique-redis-core`

`vertique-rate-limit-redis` is the CLUSTERED backend for the provider-neutral
rate-limiting contracts: a Bucket4j-native, compare-and-swap engine over the shared
Vert.x Redis client from `vertique-redis-core`. It contributes the `CLUSTERED` entry
of the core backend provider map; it owns no named Redis-profile parsing or
shared-client lifecycle.

## When To Use It

Install `RateLimitRedisModule` when at least one policy uses `mode: CLUSTERED` —
quota state shared across application instances. Configure `rateLimit.redis.*`
(this module) plus a named `redis.connections.<name>` profile (`vertique-redis-core`);
do not duplicate connection settings under `rateLimit.redis`.

## Core Concepts

One application-scoped Bucket4j `AsyncProxyManager<String>` is built from the shared
Redis client:

```text
Bucket4jVertx.casBasedBuilder(sharedRedis)
  .keyMapper(Mapper.STRING)
  .build()
  .asAsync()
```

This is the only surface the pinned `bucket4j_jdk17-vertx:8.19.0` builder exposes —
no `requestTimeout`, `maxRetries`, `expirationAfterWrite`, or client-clock hook.
Timeout, attempt-bounding, and TTL maintenance are layered on top by this module:

- **Operation deadline.** A Vert.x timer at `rateLimit.redis.operationTimeoutMs`
  bounds each clustered consume. Whichever of the deadline timer or the Bucket4j
  completion settles first wins; the other is discarded, so a completion that
  arrives after the deadline has fired is never delivered to the caller or an
  observer a second time. Expiry is ambiguous — the in-flight compare-and-swap may
  or may not have committed — and is classified `TIMEOUT`, handled per the policy's
  `failureMode`.
- **No Vertique-side retry.** Bucket4j's internal compare-and-swap loop is unbounded
  in 8.19.0; the operation deadline above is the sole bound on total elapsed time.
  There is no `maxCasAttempts` to configure.
- **TTL maintenance.** After a write that consumes or creates bucket state, this
  module issues `PEXPIRE <physicalKey> <worstCaseTimeToFullMs + expirationSlackMs>`
  on the shared client. A TTL-write failure is diagnostic-only: it is logged and
  never changes the admission decision the compare-and-swap already produced.

The physical Redis key is the CLUSTERED storage identity: `<namespace>:v1:
<keyFingerprint>:<HMAC-SHA-256 hex>`, where `keyFingerprint` is the first 8
lowercase-hex characters of `SHA-256(secret)` and the trailing segment is the full
32-byte/64-character lowercase-hex `HMAC-SHA-256` of the canonical input
`policyName:policyRevision:canonicalKeyEncoding`. Because `keyFingerprint` derives
from `rateLimit.keyDerivation.secret` itself, **rotation is: change the secret** —
the fingerprint changes with it, old-generation buckets become unreachable and
expire via TTL, and no separate key id needs to be bumped. Raw keys and HMAC output
never reach telemetry, logs, or `toString()`.

The compare-and-swap uses the acquiring JVM's wall clock — 8.19.0 exposes no
injectable client-clock hook — so participating nodes must run synchronized time;
clock skew can change which contender's refill calculation is accepted. There is no
local fallback for a CLUSTERED policy: a Redis failure classifies per the policy's
`failureMode` and never silently degrades to LOCAL state.

Each bucket is one Redis key and one cluster slot; strict enforcement requires
non-evicting Redis, ACL-scoped credentials, and monitored memory/cardinality
headroom — `rateLimit.redis.deploymentAttestedNoEviction` records that the
deployment has attested to this.

## Key Classes

### RateLimitRedisModule

The Dagger module an application installs; see Module Dagger Bindings. The
compare-and-swap backend implementation, the proxy manager, and every Bucket4j type
stay package-private inside this module — they are never Dagger bindings or public
signatures.

## Module Dagger Bindings

| Binding | Kind | Description |
|---|---|---|
| `RateLimitBackend` (`CLUSTERED`) | `@Provides @IntoMap @Singleton` | The CLUSTERED entry of the core backend provider map |
| `ApplicationShutdownStep` | `@Provides @IntoSet` | Fences the rate-limit runtime's close before the shared Redis client closes |

`RateLimitRedisModule` includes `RateLimitCoreModule` and the Redis connection
module, so installing it alone is sufficient to compose LOCAL and CLUSTERED policies
together.

## Configuration

```yaml
rateLimit:
  redis:
    connection: default
    namespace: vertique:rate-limit
    operationTimeoutMs: 50
    expirationSlackMs: 60000
    deploymentAttestedNoEviction: true
```

| Field | Bound |
|---|---|
| `operationTimeoutMs` | `1..60_000` ms |
| `expirationSlackMs` | `0..86_400_000` ms |

Endpoint, credentials, TLS, topology, and pool size live only under
`redis.connections.<name>` (`vertique-redis-core`); this module owns only the
logical namespace, its own operation deadline, and its own TTL slack.

## Verification

```text
./mvnw -ntp -pl vertique-rate-limit/vertique-rate-limit-redis -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-rate-limit-core` | Provider-neutral rate-limiting contracts and backend provider map |
| `vertique-redis-core` | Shared Redis connection and lifecycle boundary |
