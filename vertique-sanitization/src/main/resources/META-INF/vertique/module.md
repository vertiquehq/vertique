<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Sanitization Module

> **Status:** Stable
> **Package:** `dev.vertique.sanitization`
> **Artifact:** `vertique-sanitization`
> **Depends on:** core, input-processing

Provides built-in canonicalization and sanitization processors, Dagger multibinding wiring, and the `ProcessorResolver` that powers the `dev.vertique.input.processing.InputObjectProcessor` for structured request body processing.

The module bridges the annotation model defined in `dev.vertique.core.sanitization` (interfaces and annotations) with concrete implementations. Including `SanitizationModule` in a Dagger component activates all built-in processors and wires up `InputObjectProcessor` via `InputObjectProcessor.createDefault(...)`, satisfying the `@BindsOptionalOf InputObjectProcessor` declared by `RestModule`.

---

## When To Use It

Install this module whenever an application declares `@Canonicalize` or `@Sanitize` on input it
binds — a REST body or parameter, a service-dispatch payload, an MCP tool argument. It supplies the
canonicalizer and sanitizer implementations those annotations name; `dev.vertique:vertique-input-processing`
supplies the engine that walks the object graph and applies them.

Depend on it directly only to reference a canonicalizer or sanitizer class by name. An application
that installs a starter already has it on the classpath.

---

## Core Concepts

**Canonicalize, then sanitize.** Canonicalizers normalize a value into one comparable form — Unicode
NFC or NFKC, case folding, whitespace collapsing, trimming. Sanitizers then remove what must not
survive — control characters here, and HTML that is not on the allow-list with the separate
`vertique-sanitization-html` artifact. The order matters: sanitizing
before canonicalizing lets a differently-encoded form of a forbidden sequence pass.

**Policies are declared, not called.** A field carries annotations; the engine resolves the chain
once per type and applies it on every bind. Application code never invokes a sanitizer directly.

**The chain is per field, and inherited.** A policy declared on a supertype's field applies to every
subtype that inherits it, and a field-level `@SkipCanonicalization` or `@SkipSanitization` opts that
one field out of an otherwise inherited chain.

---

## Key Classes

### SanitizationModule

Abstract Dagger `@Module` that registers the eight built-in canonicalizers and the one built-in sanitizer (`StripControlCharsSanitizer`) via `@ElementsIntoSet`, declares `@Multibinds` for custom processor contributions, and provides the default `InputObjectProcessor` engine via `InputObjectProcessor.createDefault(...)`.

```java
@Component(modules = {
    VertxModule.class,
    RestModule.class,
    ValidationModule.class,
    SanitizationModule.class,  // activates all input processing
    AppModule.class,
    ResourceModule.class
})
interface AppComponent { ... }
```

When `SanitizationModule` is present, `InputObjectProcessor` is resolved via `RestModule`'s `@BindsOptionalOf` and input processing is active for all structured request bodies.

---

### ProcessorResolver

`@Singleton` that resolves `Canonicalizer` and `Sanitizer` instances by their implementation class using a two-tier lookup strategy.

| Tier | Mechanism | When to use |
|------|-----------|-------------|
| 1 | Dagger multibinding (`Set<CanonicalizerBinding>` / `Set<SanitizerBinding>`) | Processors with injected dependencies; fast path |
| 2 | Reflection (public no-arg constructor) | Stateless processors not registered via Dagger |
| Error | `IllegalStateException` | Class not registered and has no public no-arg constructor |

```java
@Inject
public ProcessorResolver(
    Set<CanonicalizerBinding> canonicalizerBindings,
    Set<SanitizerBinding> sanitizerBindings) { ... }

public Canonicalizer resolveCanonicalizer(Class<? extends Canonicalizer> type) { ... }
public Sanitizer resolveSanitizer(Class<? extends Sanitizer> type) { ... }
```

All processors registered through `SanitizationModule` are always resolvable via tier 1. A canonicalizer or sanitizer class with no binding is created through its public no-arg constructor; the HTML sanitizers in `vertique-sanitization-html` rely on this and ship no binding.

---

## Built-in Canonicalizers

Canonicalizers are semantics-preserving normalizers. They are stateless, idempotent, and thread-safe.

| Class | Effect |
|-------|--------|
| `TrimCanonicalizer` | Strips leading and trailing Unicode whitespace (`String.strip()`); more comprehensive than ASCII-only `trim()` |
| `NfkcCanonicalizer` | Normalizes to Unicode Normalization Form KC (compatibility decomposition + canonical composition); recommended for identifiers |
| `NfcCanonicalizer` | Normalizes to Unicode Normalization Form C (canonical decomposition + canonical composition); recommended for text |
| `NormalizeLineEndingsCanonicalizer` | Converts `\r\n` and bare `\r` to `\n` |
| `CollapseWhitespaceCanonicalizer` | Replaces runs of ASCII whitespace (`\s+`: space, tab, line feed, vertical tab, form feed, carriage return) with a single space |
| `UpperCaseCanonicalizer` | Converts to uppercase using `String.toUpperCase(Locale.ROOT)` |
| `LowerCaseCanonicalizer` | Converts to lowercase using `String.toLowerCase(Locale.ROOT)` |
| `RemoveIdentifierSeparatorsCanonicalizer` | Removes spaces and hyphens; useful for phone numbers, credit card numbers, UUIDs before comparison |

**Example:** `"550e8400-e29b-41d4-a716-446655440000"` → `"550e8400e29b41d4a716446655440000"` with `RemoveIdentifierSeparatorsCanonicalizer`.

---

## Built-in Sanitizers

Sanitizers remove or transform disallowed content. They are stateless and thread-safe.

| Class | Effect |
|-------|--------|
| `StripControlCharsSanitizer` | Removes C0 and C1 control characters (`U+0000`–`U+001F`, `U+007F`, `U+0080`–`U+009F`); preserves tab, LF, and CR |

The HTML sanitizers (`StripAllHtmlSanitizer`, `BasicHtmlSanitizer`, `LinksHtmlSanitizer`, `RichTextHtmlSanitizer`) are in the separate `dev.vertique:vertique-sanitization-html` artifact, package `dev.vertique.sanitization.html`.

---

## Annotation Model

Canonicalization and sanitization are driven by annotations from `dev.vertique.core.sanitization`.

### Placement and Scope

| Target | Scope |
|--------|-------|
| JAX-RS resource class (`TYPE`) | Route-level default for all operations in that resource |
| JAX-RS resource method (`METHOD`) | Overrides type-level for this operation; runs before object-level and field-level |
| DTO class (`TYPE`) | Object-level default for all fields of that type |
| Field or record component | Field-specific; runs after object-level |
| Method parameter (`PARAMETER`) | Applied to that specific scalar or bean parameter |
| Meta-annotation (`ANNOTATION_TYPE`) | Composes a custom annotation from built-in chains |

### @Canonicalize / @Sanitize

Declare an ordered chain of processors. Processors execute in declaration order.

```java
// Route-level: all requests to this resource get trimmed and NFKC-normalized first
@Path("/users")
@Canonicalize({TrimCanonicalizer.class, NfkcCanonicalizer.class})
public class UserResource { ... }

// Field-level: apply HTML sanitization to this specific field
// (BasicHtmlSanitizer: dev.vertique.sanitization.html, artifact vertique-sanitization-html)
public record CreateUserRequest(
    @Sanitize(BasicHtmlSanitizer.class)
    String bio,

    @Canonicalize({TrimCanonicalizer.class, NfkcCanonicalizer.class})
    String username
) {}
```

### @SkipCanonicalization / @SkipSanitization

Opt a field or record component out of inherited processing.

```java
public record CreateUserRequest(
    @SkipCanonicalization  // don't normalize this field
    String rawToken
) {}
```

### Composed Annotations

Use `ANNOTATION_TYPE` target to create presets:

```java
@Documented
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Canonicalize({TrimCanonicalizer.class, NfkcCanonicalizer.class})
@Sanitize(StripControlCharsSanitizer.class)
public @interface StandardText {}

// Usage:
public record CreateUserRequest(@StandardText String name) {}
```

---

## Processing Order

For each string value in a structured body, the processing order is:

1. Route-level canonicalizers (from resource class + method)
2. Object-level canonicalizers (from DTO type)
3. Field-level canonicalizers (from field/record component)
4. Route-level sanitizers
5. Object-level sanitizers
6. Field-level sanitizers

Processing runs on the intermediate map representation (after JSON parsing, before DTO materialization). A skip annotation empties only the chain of its own kind: `@SkipCanonicalization` removes the canonicalizer chain and `@SkipSanitization` removes the sanitizer chain, and neither disables the other. A skip stays set for every nested value below the element that declares it. A skip declared on an object type yields to a field that declares its own chain of that kind; a skip declared on the field itself always wins.

---

## Extension Points

### Contributing a Custom Canonicalizer

```java
@Singleton
public class NormalizeEmailCanonicalizer implements Canonicalizer {
    @Inject public NormalizeEmailCanonicalizer() {}

    @Override
    public String canonicalize(String value, InputValueContext context) {
        if (value == null) return null;
        return value.strip().toLowerCase(Locale.ROOT);
    }
}

// Register in a Dagger module:
@Provides @IntoSet
static CanonicalizerBinding emailCanonicalizer(NormalizeEmailCanonicalizer c) {
    return new CanonicalizerBinding(NormalizeEmailCanonicalizer.class, c);
}
```

### Contributing a Custom Sanitizer

```java
@Singleton
public class PhoneNumberSanitizer implements Sanitizer {
    @Inject public PhoneNumberSanitizer() {}

    @Override
    public String sanitize(String value, InputValueContext context) {
        if (value == null) return null;
        return value.replaceAll("[^+\\d]", "");
    }
}

// Register in a Dagger module:
@Provides @IntoSet
static SanitizerBinding phoneNumberSanitizer(PhoneNumberSanitizer s) {
    return new SanitizerBinding(PhoneNumberSanitizer.class, s);
}
```

### Multibinding Declarations

`SanitizationModule` declares both multibinding sets:

```java
@Multibinds abstract Set<CanonicalizerBinding> canonicalizerBindings();
@Multibinds abstract Set<SanitizerBinding> sanitizerBindings();
```

Custom processors are contributed via `@Provides @IntoSet` in application or library modules.

---

## Dependencies

- `dev.vertique:vertique-core`
- `dev.vertique:vertique-input-processing`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `org.projectlombok:lombok` (provided scope)
