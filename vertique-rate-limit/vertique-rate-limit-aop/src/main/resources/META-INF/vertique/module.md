<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Rate Limit AOP

> **Status:** Alpha
> **Package:** `dev.vertique.ratelimit.aop`
> **Artifact:** `vertique-rate-limit-aop`
> **Depends on:** `vertique-rate-limit-core`, `vertique-aop`, `vertique-core`

`vertique-rate-limit-aop` adapts one `@RateLimited` method declaration to the
provider-neutral programmatic rate-limiting API. It owns the annotation vocabulary,
selector-path resolution for annotated methods, and the Dagger binding for the
rate-limit aspect. The generic Vertique AOP processor generates the application
proxy and reflection-free method metadata; this module does not own proxy
generation, quota math, or backend behavior.

`@RateLimited` is transport-neutral: it is usable on any Dagger-provided
component — services, REST resources, and future MCP tools alike — because the
generic AOP proxy intercepts Dagger `Provider.get()` resolution, not a
transport-specific hook.

## When To Use It

Install `RateLimitAopModule` alongside `RateLimitCoreModule` (and
`vertique-rate-limit-redis` for CLUSTERED policies) when methods should declare
per-operation admission through the annotation rather than manual `RateLimiters`
calls. Add `vertique-codegen-rate-limit` to the compiler's annotation-processor path
so declarations are validated at compile time.

## Core Concepts

```java
@RateLimited(policy = "search-quota", key = {"tenantId", "userId"})
public Future<SearchResult> search(String tenantId, String userId, Query query) { ... }
```

`@RateLimited` names exactly one policy and is non-repeatable in v1: numeric limits,
algorithm, mode, revision, and failure behavior never appear in the annotation, only
in the named policy's configuration. `key()` is an ordered array of selector paths —
never a template or format string — reusing the cache path grammar: a parameter root
by name or position, dot-separated record/bean accessor segments, and a terminal
scalar type. `subject` (default `EFFECTIVE_PRINCIPAL`) and `anonymous` (default
`SHARED_BUCKET`) control identity-scoped keying (see `vertique-rate-limit-core`'s
`RateLimitSubjectResolver`/`RateLimitAdapterSupport`); `cost` (default `1`) is the
per-invocation cost. An application that uses any `subject()` other than `NONE`
must co-install the existing `AuthModule`/`SecurityModule` (or `JwtAuthModule`) so a
typed, possibly anonymous, `SecurityContext` exists before the aspect runs —
otherwise every caller resolves as anonymous.

`@Aspect(ordering = 300)` places `@RateLimited` outside `@Cacheable`(200)/
`@CacheEvict`(100) and inside `@Timed`(1000) in the generated interceptor chain.

Handle resolution happens once, at generated-proxy-constructor time: an unknown
policy name fails application startup, before any request is served, exactly as
`@RateLimited`'s handle-creation contract in `vertique-rate-limit-core` describes.
At invocation, resolved selector components are framed into a `RateLimitKey`
through the identity-framing seam; an empty framing result (an anonymous caller
under `AnonymousRateLimitPolicy.BYPASS`) bypasses admission entirely and the method
proceeds unlimited, otherwise the resolved handle's guarded `execute(key, cost,
invocation::proceed)` runs.

**Self-invocation bypass.** Calling an `@RateLimited` method on `this` from inside
the same bean, or constructing the bean directly instead of resolving it through
Dagger, bypasses the generated proxy and therefore bypasses admission entirely —
identical to `@Cacheable`'s documented behavior. Only calls that arrive through the
Dagger-resolved proxy are intercepted.

## Key Classes

### @RateLimited

`policy()` (required), `key()` (default `{}`), `subject()` (default
`RateLimitSubject.EFFECTIVE_PRINCIPAL`), `anonymous()` (default
`AnonymousRateLimitPolicy.SHARED_BUCKET`), `cost()` (default `1`).

## Module Dagger Bindings

| Binding | Kind | Description |
|---|---|---|
| `AspectProvider<RateLimited>` | `@Binds` | The generated-proxy interceptor for `@RateLimited` methods |

## Verification

```text
./mvnw -ntp -pl vertique-rate-limit/vertique-rate-limit-aop -am verify
```

## Dependencies

| Artifact | Purpose |
|---|---|
| `vertique-rate-limit-core` | Programmatic rate-limiting API the aspect delegates to |
| `vertique-aop` | Generic proxy generation and `AspectProvider`/`@Aspect` machinery |
| `vertique-core` | Framework foundations |
