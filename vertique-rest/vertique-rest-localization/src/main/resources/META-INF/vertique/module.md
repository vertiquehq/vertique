<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST Localization Module

> **Status:** Beta
> **Package:** `dev.vertique.rest.localization`
> **Artifact:** `vertique-rest-localization`
> **Depends on:** core, rest-core, localization

Provides inbound REST locale negotiation. At the start of every request the module walks an ordered set of `LocaleSource` implementations, picks the first that returns a resolved locale, and binds a `LocalizationContext` into `ContextHolder`. Handler code reads the result via `ContextHolder`; there is no parallel RoutingContext-data accessor.

The module is server-only. It has no dependency on `vertique-rest-jaxrs` or `vertique-rest-client` — that boundary is enforced by a Maven Enforcer `bannedDependencies` rule (FR-RLOC-004). Outbound Accept-Language propagation to downstream REST clients is deferred to a future `vertique-rest-client-localization` module using the `RestClientOutboundContextContributor` seam.

---

## When To Use It

Install `RestLocalizationModule` in any REST application that must serve locale-aware content and needs the negotiated locale available to JAX-RS resources, service handlers, and downstream async boundaries. `RestLocalizationModule` is declared `@Module(includes = LocalizationModule.class)`, so it pulls in `LocalizationModule` from `vertique-localization` automatically — applications add only `RestLocalizationModule`.

Pair with application-contributed `LocaleSource` implementations (cookie, `?lang=` query parameter, custom header, pre-auth token hint) to establish locale before security processing runs.

For post-auth locale (e.g., from a user-profile database record), register a higher-priority `RequestInterceptor` that re-binds a `LocalizationContext` into `ContextHolder` after authentication completes — `LocaleSource` is not the right vehicle for this because the principal is not yet known at `beforeRequest` time.

---

## Core Concepts

### LocaleSource chain

`RequestLocaleInterceptor` runs at `REQUEST_LOCALE_PRIORITY = Integer.MIN_VALUE + 1000`, earlier than any application interceptor that uses the locale. It iterates over the injected `Set<LocaleSource>` sorted by `OrderedExtension.comparator()` (phase → priority → orderKey) and binds the first non-empty result.

Sources with a lower priority value run first. The built-in `AcceptLanguageLocaleSource` declares `PRIORITY = 1000`, which places it after all application sources at the default priority of `0`. This means an application source (cookie, query parameter, etc.) wins over the `Accept-Language` header automatically when both are present.

If no source resolves a locale, the interceptor binds the configured `defaultLocale` from `LocalizationConfig` with `localeSource = "default-locale"`.

### Pre-auth vs post-auth boundary

`LocaleSource.resolve(RoutingContext)` is called during `beforeRequest`, before the security chain runs. The authenticated principal is not available at this point. Sources that need the principal (e.g., loading locale from a user-preference record) must not implement `LocaleSource` — they belong in a post-auth interceptor that calls `ContextHolder.bind(LocalizationContext.class, ctx)` after identity resolution.

### Single source of truth

The `LocalizationContext` bound in `ContextHolder` by `RequestLocaleInterceptor` is the single source of truth for the negotiated locale. Handler code reads it as:

```java
Optional<Locale> locale = ContextHolder.current(LocalizationContext.class)
        .map(LocalizationContext::locale);
```

The convenience facade `LocalizationContextHolder` (in `vertique-localization`) wraps these calls:

```java
Locale locale = LocalizationContextHolder.locale(holder, config.defaultLocale());
ZoneId  zone  = LocalizationContextHolder.zone(holder, ZoneId.of("UTC"));
```

### Propagation to downstream service calls

`LocalizationContext` is a `@DispatchContextValue`. `RestLocalizationModule` pulls in `LocalizationModule`, which registers a pass-through service-dispatch encoder/decoder pair and a durable-metadata encoder/decoder pair for `LocalizationContext` into the framework's context-propagation multibindings. Once `RequestLocaleInterceptor` binds a `LocalizationContext`, it therefore crosses in-process event-bus service calls and durable async boundaries automatically, with no extra application wiring required. This is separate from outbound REST-client propagation, which remains deferred — see the module overview above.

### Scope lifecycle

The `ContextHolder.Scope` returned by the binding call is registered with `RequestContextLifecycle.fromRoutingContext(rc).onClose(scope)`. The scope is closed when the request context is torn down at request completion. If `onClose` registration itself throws, the interceptor closes the scope immediately to prevent a leak.

---

## Key Classes

### LocaleSource

SPI interface for locale negotiation strategies. `LocaleSource extends OrderedExtension` and is sorted by `OrderedExtension.comparator()` — phase ascending, then priority ascending, then `orderKey` (default FQCN) as a stable tie-break. The first source returning a non-empty result wins.

```java
public interface LocaleSource extends OrderedExtension {
    Optional<ResolvedLocale> resolve(RoutingContext rc);
}
```

| Aspect | Behavior |
|--------|----------|
| Priority | Lower value runs earlier (default `0`); sources at the same priority are ordered by `orderKey` (FQCN) |
| Return | `Optional.empty()` to defer to the next source; `Optional.of(resolved)` to claim the locale |
| Errors | Should not throw; the interceptor catches `RuntimeException`, WARN-throttles per source class, and treats the source as having deferred |

Contributed via Dagger `@IntoSet`:

```java
@Provides
@Singleton
@IntoSet
static LocaleSource cookieLocaleSource(LocalizationConfig config) {
    return new CookieLocaleSource(config);
}
```

### ResolvedLocale

Immutable record carrying a resolved locale and a short diagnostic label.

```java
public record ResolvedLocale(Locale locale, String source) {
    public static ResolvedLocale of(Locale locale, String source) { ... }
}
```

A blank `source` value normalizes to `"unspecified"`. The `source` string becomes the `localeSource` field of the bound `LocalizationContext`. Framework-defined values: `"rest-accept-language"`, `"default-locale"`. Application-defined examples: `"cookie"`, `"query-param"`, `"user-profile"`.

### AcceptLanguageLocaleSource

Built-in `LocaleSource` that negotiates the `Accept-Language` request header using `LocaleResolver.requireLanguageRange` (strict — never falls back to a default locale on its own).

```java
@Singleton
public class AcceptLanguageLocaleSource implements LocaleSource {
    public static final int PRIORITY = 1000;
    // ...
}
```

| Condition | Result |
|-----------|--------|
| Header absent or blank | Defer (`Optional.empty()`); no WARN |
| Wildcard-only `*` (RFC 9110) | Defer to configured default; no WARN |
| Malformed or unsupported value | Defer + WARN throttled once per `LocaleResolutionException.Reason` |
| Valid supported locale | `ResolvedLocale.of(locale, "rest-accept-language")` |

The WARN throttle uses a lock-free `AtomicInteger` bitmask keyed by `LocaleResolutionException.Reason` ordinal, scoped to the AppComponent lifetime. This means at most one WARN per reason value per JVM process startup, preventing log flooding on repeated bad headers.

#### Invariants & Gotchas

`AcceptLanguageLocaleSource` uses `requireLanguageRange`, not the lenient `resolveLanguageRange`. This means a malformed or unsupported header causes a deferral (and a throttled WARN), not a 400/406 response. The module does not reject requests on locale errors — it always falls through to the configured default. Applications that need to reject invalid `Accept-Language` values must contribute a separate `RequestInterceptor` or `ErrorInterceptor`.

### RequestLocaleInterceptor

`RequestInterceptor` that drives the `LocaleSource` chain and binds the result.

```java
@Singleton
public class RequestLocaleInterceptor implements RequestInterceptor {
    public static final int REQUEST_LOCALE_PRIORITY = Integer.MIN_VALUE + 1000;
    // ...
}
```

In `beforeRequest`:
1. Iterates `Set<LocaleSource>` sorted by `OrderedExtension.comparator()` (phase → priority → orderKey ASC).
2. Catches `RuntimeException` from each source, WARN-throttles per source class, and treats the source as deferred.
3. Binds the first non-empty `ResolvedLocale` as `LocalizationContext` with `zoneSource = "default-zone"` and zone from `LocalizationConfig.defaultZone()`.
4. On no match, binds `LocalizationContext` using `defaultLocale` (`localeSource = "default-locale"`) and `defaultZone` (`zoneSource = "default-zone"`).
5. Registers the resulting `Scope` via `RequestContextLifecycle.fromRoutingContext(rc).onClose(scope)`; closes immediately on registration failure.

`REQUEST_LOCALE_PRIORITY = Integer.MIN_VALUE + 1000` places locale resolution very early in the request interceptor order, before any application interceptor that reads the locale. A future v2 response-localization interceptor (Content-Language header, localized error bodies) must use a distinct constant and must not reuse this value.

### RestLocalizationModule

Abstract Dagger `@Module` that wires the entire module.

```java
@Module(includes = LocalizationModule.class)
public abstract class RestLocalizationModule { ... }
```

It:
- Declares `@Multibinds Set<LocaleSource>` (so the set is non-null even with no application sources).
- Contributes `AcceptLanguageLocaleSource` via a static `@Provides @IntoSet` method.
- Contributes `RequestLocaleInterceptor` via a static `@Provides @IntoSet` method into the `Set<RequestInterceptor>` multibinding.

Applications include only `RestLocalizationModule` — `LocalizationModule` is pulled in automatically.

---

## Extension Points

### LocaleSource (inbound locale strategy)

The primary extension point. Implement `LocaleSource` and contribute it via `@Provides @IntoSet` for each locale signal the application supports.

**Example — query-parameter locale source:**

```java
@Singleton
public class QueryParamLocaleSource implements LocaleSource {

    private static final String PARAM = "lang";
    private final LocaleResolver localeResolver;

    @Inject
    QueryParamLocaleSource(LocaleResolver localeResolver) {
        this.localeResolver = localeResolver;
    }

    @Override
    public Optional<ResolvedLocale> resolve(RoutingContext rc) {
        String value = rc.request().getParam(PARAM);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            Locale locale = localeResolver.requireLanguageRange(value);
            return Optional.of(ResolvedLocale.of(locale, "query-param"));
        } catch (LocaleResolutionException ex) {
            return Optional.empty(); // defer; caller may add validation separately
        }
    }
}
```

Wire it:

```java
@Module
abstract class AppLocalizationModule {

    @Binds
    @IntoSet
    abstract LocaleSource queryParamLocaleSource(QueryParamLocaleSource impl);
}
```

The `examples/vertique-example-localization` example shows this pattern end-to-end.

**Priority guide:**

| Source type | Suggested priority |
|-------------|-------------------|
| Application sources (cookie, query param, session, pre-auth header) | `0` (default) — run before `AcceptLanguageLocaleSource` |
| Built-in `AcceptLanguageLocaleSource` | `1000` — runs last among sources |
| Post-auth sources | Use a separate `RequestInterceptor`, not `LocaleSource` |

---

## Dependencies

- `dev.vertique:vertique-core` — `ContextHolder`, `ContextHolder.Scope`, common exceptions
- `dev.vertique:vertique-rest-core` — `RequestInterceptor`, `RequestContextLifecycle`
- `dev.vertique:vertique-localization` — `LocalizationModule`, `LocalizationContext`, `LocalizationConfig`, `LocaleResolver`, `LocaleResolutionException`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `io.vertx:vertx-web` (RoutingContext)

Maven Enforcer `bannedDependencies` excludes `vertique-rest-jaxrs` and `vertique-rest-client` from the compile classpath (FR-RLOC-004). The module is server-side only and carries no JAX-RS runtime or HTTP-client dependency.
