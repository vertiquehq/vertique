<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Localization Module

> **Status:** Alpha
> **Package:** `dev.vertique.localization`
> **Artifact:** `vertique-localization`
> **Depends on:** core

Provides HTTP-agnostic localization infrastructure: ResourceBundle-backed message catalogs, deterministic RFC 4647 / RFC 5646 locale negotiation, and a typed `LocalizationContext` value for carrying locale, time-zone, and formatting preferences across a unit of work.

The module deliberately has no REST dependency. HTTP integration (Accept-Language extraction, Content-Language response headers, localized ProblemDetail / validation messages) lives in the separate `vertique-rest-localization` module. Each application or framework module owns and qualifies its own `MessageSource` — there is no global or aggregated bundle.

---

## When To Use It

Install `LocalizationModule` in any application that needs to serve locale-aware messages, needs deterministic locale negotiation from `Accept-Language` or language-tag lists, or needs to carry locale / zone preferences through service calls and durable async boundaries. Durable propagation requires `ContextRuntimeModule` from `vertique-context` in the same AppComponent (see ADR-0066).

Pair with `vertique-rest-localization` (v1 inbound shipped — binds `LocalizationContext` at REST inbound via the `LocaleSource` chain; see `dev.vertique:vertique-rest-localization`) to add REST-side locale extraction and localized error responses.

---

## Core Concepts

### No global MessageSource

Every `MessageSource` is application-owned and Dagger-qualified. `LocalizationModule` provides a `MessageSourceFactory` singleton; applications call `factory.create(...)` inside their own `@Module` and bind the result under a custom qualifier. This prevents bundle coupling across modules and keeps each module's message namespace isolated.

### Candidate-locale walk

`DefaultMessageSource` evaluates the requested locale, then (if different) the configured default locale, then (only when `fallbackToSystemLocale = true`) the JVM system locale. For each candidate it tries the primary basename, then the declared fallback basenames in order. The first bundle containing the message code wins. ResourceBundle's own system-locale fallback mechanism is disabled; the framework controls the walk.

### Exception data, not exception type

`LocaleResolutionException` is `final` and carries a `Reason` enum (`MISSING_INPUT`, `MALFORMED_LANGUAGE_RANGE`, `MALFORMED_LANGUAGE_TAG`, `UNSUPPORTED_LANGUAGE_RANGE`, `UNSUPPORTED_LANGUAGE_TAG`). Boundary modules switch on `reason()` to decide HTTP status (400 for malformed, 406 for unsupported) without catching by subtype. Similarly, `NoSuchMessageException` carries `bundleBaseName()` and `code()` so REST mappers have structured data to work with.

---

## Key Classes

### MessageSource

SPI for resolving and formatting localized messages backed by Java `ResourceBundle`. The four method groups are:

| Method | Behavior on missing code |
|--------|--------------------------|
| `getMessage(code, locale, args...)` | Throws `NoSuchMessageException` |
| `getMessage(code, defaultMessage, locale, args...)` | Returns `defaultMessage`; throws only when `defaultMessage` is also `null` |
| `findMessage(code, locale, args...)` | Returns `Optional.empty()` (never applies `useCodeAsDefaultMessage`) |
| `getMessage(resolvable, locale)` | Tries codes in order; falls back to `resolvable.defaultMessage()`; throws for first code on total miss |

Null `code` or `locale` → `NullPointerException`. Blank `code` → `NoSuchMessageException`. Null `args` array → treated as empty (FR-LOC-083).

### MessageSourceFactory

Singleton SPI factory for creating `MessageSource` instances. Two creation overloads:

```java
// Simple: single bundle
MessageSource source = factory.create("messages");

// With fallbacks and class-loader hint
MessageSource source = factory.create(
    MessageSourceOptions.builder()
        .basename("customer-messages")
        .fallbackBasename("common-messages")
        .classLoader(getClass().getClassLoader())
        .build());
```

`DefaultMessageSourceFactory` is the shipped implementation, bound by `LocalizationModule`.

### MessageSourceOptions

Immutable record configured via a Lombok builder. Compact constructor validates:
- `basename` must not be null or blank.
- `fallbackBasenames` elements must not be null or blank.
- The full set of basenames (primary + fallbacks) must be duplicate-free.

```java
@Builder(toBuilder = true)
public record MessageSourceOptions(
        String basename,
        @Singular("fallbackBasename") List<String> fallbackBasenames,
        ClassLoader classLoader,
        Class<?> caller) { ... }
```

#### Invariants & Gotchas

The `BundleControl` instance (`ResourceBundle.Control` subclass) is stateless and shared across all lookups within a single `MessageSource`. Java's bundle cache is keyed by `(classloader, basename, locale, control)` — passing a new `Control` object per call silently invalidates the cache and defeats TTL semantics.

When modules ship their own bundles from a separately-loaded JAR, they must supply that JAR's `ClassLoader` via `MessageSourceOptions.classLoader(...)` or the `caller` hint, or `ResourceBundle.getBundle` will fail to locate the properties files.

### MessageResolvable

Immutable record carrying an ordered list of message codes, optional format arguments, and an optional default message. Used when a domain type needs to express itself as a localizable descriptor without coupling to a specific bundle.

```java
new MessageResolvable(
    List.of("order.not-found", "entity.not-found"),  // tried left-to-right
    List.of(orderId),
    "Order " + orderId + " was not found");           // fallback if no code resolves
```

Compact constructor invariants: `codes` must not be null or empty; null elements throw `NullPointerException` (via `List.copyOf`); blank elements throw `IllegalArgumentException`. Null `args` is normalized to `List.of()`.

### MessageCoded

Single-method SPI for domain objects that know how to express themselves as a `MessageResolvable`:

```java
public interface MessageCoded {
    MessageResolvable message();
}
```

Implement on domain exceptions or value objects to allow callers to localize them via any `MessageSource` without coupling the domain type to a bundle or locale.

```java
public class OrderNotFoundException extends RuntimeException implements MessageCoded {
    private final String orderId;

    public OrderNotFoundException(String orderId) {
        super("Order not found: " + orderId);
        this.orderId = orderId;
    }

    @Override
    public MessageResolvable message() {
        return new MessageResolvable(
            List.of("order.not-found"),
            List.of(orderId),
            "Order " + orderId + " was not found");
    }
}
```

### LocaleResolver

SPI for deterministic locale negotiation. Two resolution paths, each in three overload flavors:

| Method | Path | On failure |
|--------|------|-----------|
| `resolveLanguageRange(value)` | RFC 4647 | Returns `defaultLocale()` |
| `resolveLanguageRange(value, fallback)` | RFC 4647 | Returns `fallback`; throws when `fallback` is null |
| `requireLanguageRange(value)` | RFC 4647 | Always throws `LocaleResolutionException` |
| `resolveLanguageTags(value)` | RFC 5646 | Returns `defaultLocale()` |
| `resolveLanguageTags(value, fallback)` | RFC 5646 | Returns `fallback`; throws when `fallback` is null |
| `requireLanguageTags(value)` | RFC 5646 | Always throws `LocaleResolutionException` |

The language-tag path (`resolveLanguageTags`) accepts mixed comma/whitespace separators (e.g. `"de-DE  fr  en"`). Whitespace runs are normalized to commas before matching, but error reporting preserves the original raw value.

Implementations only need to override the explicit-fallback overloads (`resolveLanguageRange(value, fallback)` and `resolveLanguageTags(value, fallback)`); the other overloads are `default` methods on the interface.

### LocaleResolutionException

Final exception carrying structured failure data. Boundary modules use `reason()` instead of catching by subtype:

```java
try {
    locale = localeResolver.requireLanguageRange(headerValue);
} catch (LocaleResolutionException ex) {
    return switch (ex.reason()) {
        case MALFORMED_LANGUAGE_RANGE, MALFORMED_LANGUAGE_TAG -> Response.status(400).build();
        case UNSUPPORTED_LANGUAGE_RANGE, UNSUPPORTED_LANGUAGE_TAG -> Response.status(406).build();
        case MISSING_INPUT -> Response.status(400).build();
    };
}
```

Fields: `reason()` (`Reason` enum), `value()` (raw input, never normalized — null stays null), `supportedLocales()` (immutable snapshot of the resolver's full supported list at throw time).

Static factory methods: `missingLanguageRange`, `missingLanguageTag`, `malformedLanguageRange`, `malformedLanguageTag`, `unsupportedLanguageRange`, `unsupportedLanguageTag`.

### LocalizationConfig

Immutable record produced by `LocalizationConfigParser` from the application's `localization.*` JSON tree:

| Field | Default | Notes |
|-------|---------|-------|
| `defaultLocale` | `en` | Must be a member of `supportedLocales` |
| `defaultZone` | `UTC` | Never falls back to JVM system zone; blank/invalid values fail at startup |
| `supportedLocales` | `[defaultLocale]` | Non-empty; defines the resolver's candidate set |
| `fallbackToSystemLocale` | `false` | Enables JVM-default-locale as last-resort candidate |
| `useCodeAsDefaultMessage` | `false` | Applies only to throwing `getMessage` overloads, not `findMessage` |
| `alwaysUseMessageFormat` | `false` | Forces `MessageFormat` even when no args are supplied |
| `cacheTtlSeconds` | `-1` | `-1` = JVM default; `0` = no cache; must be integer-typed in JSON |

Sample configuration:

```json
{
  "localization": {
    "defaultLocale": "en",
    "defaultZone": "UTC",
    "supportedLocales": ["en", "fi", "sv"],
    "messages": {
      "useCodeAsDefaultMessage": false,
      "alwaysUseMessageFormat": false,
      "cacheTtlSeconds": 3600
    }
  }
}
```

#### Invariants & Gotchas

`cacheTtlSeconds` is read via `JsonObject.getValue()` rather than `getString()` / `getDouble()`. Only `Integer` and `Long` JSON types are accepted — a JSON floating-point value such as `3600.0` throws `ConfigurationException`. This is intentional: unit-suffixed duration fields in this framework must be integer-typed.

`defaultZone` defaults to UTC when the key is **absent** but throws `ConfigurationException` when the key is **present** with a blank or invalid value. Missing ≠ blank.

### LocalizationContext

Immutable record carrying locale, zone, and optional formatting preferences for a unit of work. Annotated `@DispatchContextValue` (and a `ContextValue`) for future in-process propagation.

```java
@DispatchContextValue
public record LocalizationContext(
        Locale locale,
        ZoneId zone,
        Optional<String> currency,
        Optional<String> calendar,
        Optional<String> numberingSystem,
        String localeSource,
        String zoneSource) { ... }
```

Compact constructor invariants: `locale` and `zone` must not be null. Null `Optional` sub-fields normalize to `Optional.empty()`. Null or blank `localeSource` / `zoneSource` normalize to `"unspecified"`.

Convenience accessor `languageTag()` returns `locale.toLanguageTag()` (e.g. `"sv-FI"`).

Framework-produced `localeSource` values: `rest-accept-language`, `default-locale`, `persisted-metadata`, `unspecified`. Framework-produced `zoneSource` values: `default-zone`, `persisted-metadata`, `unspecified`. Applications may add their own values (e.g. `user-profile`).

`LocalizationContext` propagates automatically across in-process service-dispatch and durable async boundaries whenever `LocalizationModule` and `ContextRuntimeModule` are both installed. See ADR-0066 for the codec design and decode contract.

### LocalizationContextHolder

Stateless typed facade over `ContextHolder` that eliminates the need to repeat the
`Class<LocalizationContext>` token at call sites.

```java
// Read (lenient outside Vert.x — returns empty)
Optional<LocalizationContext> ctx = LocalizationContextHolder.current(holder);
Locale locale                     = LocalizationContextHolder.locale(holder, config.defaultLocale());
ZoneId  zone                      = LocalizationContextHolder.zone(holder, ZoneId.of("UTC"));

// Bind (fails fast outside a duplicated context)
try (ContextHolder.Scope scope = LocalizationContextHolder.bind(holder, ctx)) {
    // ctx is bound for the duration of this scope
}
```

### Service-dispatch codec (identity)

`LocalizationContextServiceDispatchEncoder` returns the value unchanged (identity pass-through).
`LocalizationContextServiceDispatchDecoder` has three cases: a `LocalizationContext` instance →
`ContextDecodeResult.of(cast)`; `null` → `empty()`; wrong type → `failure(...)`. Both are
registered via `LocalizationModule` into the Dagger `Set<ServiceDispatchContextEncoder<?>>` /
`Set<ServiceDispatchContextDecoder<?>>` multibindings.

The identity pattern mirrors `SecurityContext` propagation — snapshotting would produce an identical
copy at allocation cost with no safety benefit because `LocalizationContext` is a final record.

### Durable codec (`LocalizationContextDurableEncoder` / `LocalizationContextDurableDecoder`)

The encoder serializes `LocalizationContext` into a single `localization` namespace in a
`DurableMetadata` document. The decoder reverses this; both are registered via `LocalizationModule`.

**Namespace shape** (defined by `LocalizationDurableNamespace`):

| Field | Required | Description |
|-------|----------|-------------|
| `locale` | yes | BCP 47 language tag |
| `zone` | yes | IANA time-zone identifier |
| `localeSource` | yes | Low-cardinality diagnostic |
| `zoneSource` | yes | Low-cardinality diagnostic |
| `currency` | no | ISO 4217 currency code; omitted when empty |
| `calendar` | no | BCP 47 `-u-ca` subtag; omitted when empty |
| `numbering` | no | BCP 47 `-u-nu` subtag; omitted when empty |

**Decoder contract** (three cases):

1. `localization` namespace absent → `empty()` (no warning).
2. `locale` or `zone` missing/blank → `failure(...)` with `"required-field-missing"` warnings; no
   partial bind.
3. Required fields present → `locale` parsed via `Locale.Builder.setLanguageTag` (strict BCP 47)
   and validated against `LocaleResolver.supportedLocales()`; `zone` parsed via `ZoneId.of`. Parse
   failures produce `failure(...)`. Optional fields read leniently — a non-string value drops the
   field with a `"unparseable-<field>"` warning but the result is still a success with warnings.
   Sources default to `"persisted-metadata"` when absent.

### LocalizationDurableNamespace

Constant container (`dev.vertique.localization.context`) holding the `NAMESPACE = "localization"` 
string and all field-name constants (`LOCALE`, `ZONE`, `LOCALE_SOURCE`, `ZONE_SOURCE`, `CURRENCY`,
`CALENDAR`, `NUMBERING`). Use these constants in any code that reads or writes the `localization`
namespace body to avoid string literals.

### Exception Hierarchy

```
VertiqueException (core)
  └── LocalizationException          (semantic-neutral module base — no HTTP status)
        ├── MessageSourceException   (message-subsystem failures)
        │     └── NoSuchMessageException  (carries bundleBaseName + code)
        └── LocaleResolutionException     (final; carries Reason enum + value + supportedLocales)
```

`LocalizationException` is intentionally semantic-neutral. REST callers in `vertique-rest-localization` decide HTTP status via exception-mapper customizers; the localization module does not carry HTTP knowledge.

---

## Extension Points

### MessageSource (custom implementations)

Applications may implement `MessageSource` directly for non-ResourceBundle backends (database-backed messages, remote CMS, etc.). The factory SPI is the only way to create the default ResourceBundle-backed implementation; custom implementations can be contributed directly as Dagger bindings.

### MessageSourceFactory (replacement)

The `@Binds MessageSourceFactory ← DefaultMessageSourceFactory` binding in `LocalizationModule` can be overridden in the application's Dagger component by providing a custom `MessageSourceFactory` binding.

### LocaleResolver (replacement)

The `@Binds LocaleResolver ← DefaultLocaleResolver` binding can be overridden to plug in a custom resolution strategy (database-stored locale preferences, user profile lookup, etc.). Implementations must override the explicit-fallback overloads; the default methods on the interface handle the other six overloads.

### Application-qualified MessageSources (primary extension pattern)

The standard way to extend localization is to create application-owned, Dagger-qualified `MessageSource` bindings using the injected factory:

```java
@Qualifier
@Retention(RUNTIME)
public @interface CustomerMessages {}

@Module
abstract class AppMessagesModule {

    @Provides
    @Singleton
    @CustomerMessages
    static MessageSource customerMessages(MessageSourceFactory factory) {
        return factory.create(MessageSourceOptions.builder()
                .basename("customer-messages")
                .fallbackBasename("common-messages")
                .build());
    }
}
```

Inject the qualified binding wherever message resolution is needed:

```java
@Singleton
public class OrderService {

    private final MessageSource customerMessages;

    @Inject
    OrderService(@CustomerMessages MessageSource customerMessages) {
        this.customerMessages = customerMessages;
    }

    public String formatOrderCreated(String orderId, Locale locale) {
        return customerMessages.getMessage("order.created", locale, orderId);
    }
}
```

---

## Dependencies

- `dev.vertique:vertique-core` — `VertiqueException`, `ConfigurationException`, `JsonConfigPaths`, `@DispatchContextValue`, `@VertxConfig`, `DurableContextMetadataEncoder/Decoder`, `ServiceDispatchContextEncoder/Decoder`, `ContextDecodeResult`, `ContextHolder`
- `io.vertx:vertx-core` — `JsonObject` (config parsing and durable codec)
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `org.projectlombok:lombok` (provided scope — `MessageSourceOptions` builder)

Maven Enforcer `bannedDependencies` rule excludes all `dev.vertique:vertique-rest-*` artifacts to keep the module REST-free (AC-LOC-005).

`ContextRuntimeModule` from `vertique-context` is not a compile-time dependency of this module — it declares the multibinding sets that `LocalizationModule` contributes into, but `LocalizationModule` need not depend on `vertique-context` directly. Applications include both modules in their AppComponent.

---

## Related ADRs

- ADR-0066: Localization context propagation — establishes the identity service-dispatch codec and the `localization` durable namespace shape, including the three-case decode contract and strict BCP 47 locale validation.
- ADR-0065: Structured durable context metadata — establishes `DurableMetadata` and the namespace model that the localization durable codec builds on.
