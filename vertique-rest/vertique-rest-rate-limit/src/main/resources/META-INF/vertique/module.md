<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Rest Rate Limit

> **Status:** Alpha
> **Package:** `dev.vertique.rest.ratelimit`
> **Artifact:** `vertique-rest-rate-limit`
> **Depends on:** `vertique-rate-limit-core`, `vertique-rest-core`, `vertique-rest-security` (narrow: `RequestOrigin` type and the `OriginCaptureMiddleware.ORDER` constant only)

`vertique-rest-rate-limit` is the thin REST adapter for the provider-neutral
rate-limiting contracts. It owns exactly two things: HTTP response mapping for
`RateLimitExceededException`/`RateLimitUnavailableException`, and one optional,
config-driven, pre-authorization edge admission `Middleware`. It owns no quota math,
no engine, and no per-operation admission mechanism beyond what
`vertique-rate-limit-aop`'s `@RateLimited` already provides on JAX-RS resources.

## When To Use It

Install `RestRateLimitModule` in any REST application that uses `@RateLimited` on a
JAX-RS resource method (for the 429/503 mapping) or that wants a pre-authorization,
router-level admission layer in front of every request (the optional edge limiter).
Co-install `vertique-rest-security`'s `AuthModule` whenever an edge rule uses the
`IP` dimension — `RestRateLimitModule` does not install it automatically.

## Core Concepts

**HTTP response mapping.** Any `RateLimitExceededException` — raised from an
annotated resource method or from a service reached via REST — maps to `429` with
`Retry-After: max(1, ceil(retryAfter / 1s))` and `Cache-Control: no-store`.
`RateLimitUnavailableException` maps to `503` with `Cache-Control: no-store` and no
quota headers. `PERMITTED`, `BACKEND_FAILURE_OPEN`, and `DISABLED` outcomes continue
the request with no rate-limit response fields. Every `429`/`503` problem body
carries one fixed, generic problem code — never `policyName`, a rule identity, key,
identity, mode, or any other rate-limit-internal detail.

**Optional edge limiter.** A single `Middleware`, mounted at `MiddlewareScope.ROOT`
with priority `OriginCaptureMiddleware.ORDER + 20`, runs before any resource mount
and before the body handler — strictly earlier than any per-operation admission
point, and strictly after origin capture so a captured client IP is available
whenever an `IP`-keyed rule evaluates. Because the edge limiter runs
pre-authorization while `@RateLimited` runs post-authorization (at method-invocation
time), unauthenticated edge traffic never poisons a per-operation business quota.

A `QUOTA_EXCEEDED`/`BACKEND_FAILURE_CLOSED` edge decision delegates via
`ctx.fail(status, exception)` into the full JAX-RS failure pipeline — the same
`ErrorPipeline`/`ResponsePipeline` (interceptor chains, `ProblemDetail` instance
enrichment, profile-aware error serialization) a resource method's own thrown
exception traverses, resolved through the mount's own real `ExceptionMapperRegistry`
rather than any middleware-local rendering. An application that contributes its own
`ExceptionMapper<RateLimitExceededException>`/`ExceptionMapper<RateLimitUnavailableException>`
(or a common supertype such as `ExceptionMapper<Throwable>`) overrides the edge
denial's response the same way it already overrides one thrown by `execute()`, with
no separate customization surface to learn — and a throwing application mapper falls
through to the pipeline's own `500` fallback, exactly as it would for an `execute()`
exception. With no application override, the packaged exception mappers render the
"HTTP response mapping" shape above; with no rate-limit-specific mapper installed at
all, `RateLimitExceededException` still renders `429` (with `Retry-After` threaded
from the decision) because it extends the core `TooManyRequestsException` semantic
root, and `RateLimitUnavailableException` still renders `503` because it extends the
core `UnavailableException` root — both via `RestModule.defaultExceptionMapper()`'s
built-in mappings. Two admission paths carry no `RateLimitDecision` at all — an
absent `RequestOrigin` under `failureMode: CLOSED`, and a defensive internal failure
— and always render the core `503` default directly (a fixed, redacted message, no
decision to mint a rate-limit-specific exception from). A denial on a request path
matching no mount at all is an accepted degradation: the correct status still reaches
the client via `ctx.fail`, but the body is Vert.x's own plain-text unhandled-failure
response, with no `Retry-After` and an `ERROR`-level Vert.x log line — the full
pipeline dressing above is reachable only once a request has matched a mount. The
redaction guarantee above (no `policyName`, key, identity, mode, or other
rate-limit-internal detail) applies to the framework's own mappers/defaults only; an
application-contributed mapper choosing to expose more is its own decision.

Each configured rule names one policy and composes its key dimensions, in declared
order, into one `RateLimitKey`:

- `GLOBAL` — the empty dimension; valid only alone in a rule.
- `IP` — reads the already-captured, already-trusted-proxy-resolved
  `RequestOrigin.clientIp()` published by `vertique-rest-security`'s
  `OriginCaptureMiddleware`; this adapter never parses forwarding headers itself and
  never falls back to the raw socket address. `ipv6PrefixBits` (`8..128`, default
  `64`) aggregates an IPv6 client IP onto an allocation-sized prefix; IPv4 always
  keys on the full address.
- `HEADER` — the named request header's value; requires `headerName`. A missing
  header, or one repeated more than once on the request, follows `missingDimension`
  (`SHARED_BUCKET`: all such callers share one bucket; `BYPASS`: skip this rule for
  the request).

All enabled rules apply to every request — a global ceiling, a per-IP limit, and a
per-header limit can all be enforced simultaneously. Rules are evaluated in declared
order and the first non-permitting decision wins; tokens already consumed by an
earlier-permitting rule are not refunded when a later rule rejects.

**Cardinality caution.** `HEADER` values are caller-controlled, and a full-address
IPv6 `IP` rule is likewise attacker-expandable (one attacker controlling a `/64` or
larger allocation can mint effectively unlimited distinct addresses). Choose
`failureMode` for `HEADER`-keyed and IPv6 `IP`-keyed rules with that in mind; IPv4's
much smaller address space makes it far harder to multiply.

**OPEN + CLUSTERED hot-key guidance.** A `GLOBAL` or other coarse shared-bucket edge
rule intended as flood protection should use `mode: LOCAL` or `failureMode: CLOSED`.
`OPEN` combined with `CLUSTERED` on a hot shared key degrades to advisory-only under
the very flood it is meant to shed: heavy contention on one shared Redis key drives
the operation past its deadline, which classifies as a timeout that `OPEN` then
fails open on. An isolated Redis connection profile for edge policies is recommended
so contention there cannot degrade unrelated policies. This is guidance, not a
mechanism change.

Startup validation rejects: `GLOBAL` combined with any other dimension; `HEADER`
without `headerName`; an edge rule using `IP` when the `OriginCaptureMiddleware`
binding is absent from the Dagger graph (i.e. `vertique-rest-security`'s
`AuthModule` was not co-installed); and `ipv6PrefixBits` outside `8..128`.

## JAX-RS Integration

The two exception mappers this module contributes plug into the standard JAX-RS
error-customization pipeline the same way any other framework-provided mapper does;
an application does not register them itself. Successful responses emit no
rate-limit fields by default, and no response ever exposes key, identity, mode, or
backend detail. `@RateLimited` itself is declared on the JAX-RS resource method by
`vertique-rate-limit-aop` — this module contributes no annotation of its own.

## Module Dagger Bindings

| Binding | Kind | Description |
|---|---|---|
| Exception mappers | `@Provides @IntoSet` | `429`/`503` mapping for `RateLimitExceededException`/`RateLimitUnavailableException` |
| Edge `Middleware` | `@Provides @IntoSet` | Contributed only when `rateLimit.rest.edge.enabled` is `true` |

`RestRateLimitModule` includes `RateLimitCoreModule`. It does not itself contribute
or require `vertique-rest-security`'s `AuthModule`/`SecurityModule`; co-installing
that module remains the application's responsibility, enforced at startup only when
an edge rule actually uses the `IP` dimension.

## Configuration

```yaml
rateLimit:
  rest:
    edge:
      enabled: true
      path: "/*"
      rules:
        - policy: edge-global
          key: [GLOBAL]
        - policy: edge-ip
          key: [IP]
        - policy: edge-apikey
          key: [HEADER]
          headerName: X-Api-Key
          missingDimension: SHARED_BUCKET
```

`path` scopes which mounts the middleware covers (`Middleware.path()` otherwise
defaults to all mounts, WebSocket/SSE/static included). `defaultMode` (core
configuration) applies to any rule policy that omits an explicit `mode`.

## Verification

```text
./mvnw -ntp -pl vertique-rest/vertique-rest-rate-limit -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-rate-limit-core` | Programmatic rate-limiting API and decision/outcome model |
| `vertique-rest-core` | JAX-RS routing, error pipeline, and `Middleware`/`MiddlewareScope` contracts |
| `vertique-rest-security` | `RequestOrigin` type and the `OriginCaptureMiddleware.ORDER` constant only, for the `IP` edge dimension |
