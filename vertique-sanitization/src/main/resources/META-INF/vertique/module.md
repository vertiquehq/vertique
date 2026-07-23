<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Sanitization Module

> **Status:** Implemented
> **Package:** `dev.vertique.sanitization`
> **Artifact:** `vertique-sanitization`
> **Depends on:** core, rest-core

Provides built-in canonicalization and sanitization processors, Dagger multibinding wiring, and the `ProcessorResolver` that powers the `InputObjectProcessor` for structured request body processing.

The module bridges the annotation model defined in `dev.vertique.core.sanitization` (interfaces and annotations) with concrete implementations. Including `SanitizationModule` in a Dagger component activates all built-in processors and wires up `InputObjectProcessor`, satisfying the `@BindsOptionalOf InputObjectProcessor` declared by `RestModule`.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.sanitization` | `SanitizationModule`, `ProcessorResolver` |
| `dev.vertique.sanitization.canonicalize` | 8 built-in `Canonicalizer` implementations |
| `dev.vertique.sanitization.sanitize` | 5 built-in `Sanitizer` implementations |

---

## Key Classes

### SanitizationModule

Abstract Dagger `@Module` that registers all built-in canonicalizers and sanitizers via `@ElementsIntoSet`, declares `@Multibinds` for custom processor contributions, and wires up `InputPolicyMetadataResolver` and `DefaultInputObjectProcessor`.

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

All processors registered through `SanitizationModule` are always resolvable via tier 1. Custom processors without Dagger bindings are resolvable via tier 2 if they expose a public no-arg constructor.

---

## Built-in Canonicalizers

Canonicalizers are semantics-preserving normalizers. They are stateless, idempotent, and thread-safe.

| Class | Effect |
|-------|--------|
| `TrimCanonicalizer` | Strips leading and trailing Unicode whitespace (`String.strip()`); more comprehensive than ASCII-only `trim()` |
| `NfkcCanonicalizer` | Normalizes to Unicode Normalization Form KC (compatibility decomposition + canonical composition); recommended for identifiers |
| `NfcCanonicalizer` | Normalizes to Unicode Normalization Form C (canonical decomposition + canonical composition); recommended for text |
| `NormalizeLineEndingsCanonicalizer` | Converts `\r\n` and bare `\r` to `\n` |
| `CollapseWhitespaceCanonicalizer` | Replaces runs of whitespace with a single space |
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
| `StripAllHtmlSanitizer` | Strips all HTML tags using OWASP Java HTML Sanitizer |
| `BasicHtmlSanitizer` | Allows `<p>`, `<br>`, `<em>`, `<i>`, `<strong>`, `<b>`, `<ul>`, `<ol>`, `<li>`; strips everything else |
| `LinksHtmlSanitizer` | Allows `BasicHtml` elements plus `<a href>` (http/https schemes only) |
| `RichTextHtmlSanitizer` | Allows `LinksHtml` elements plus headings, blockquote, code, pre, img |

HTML sanitizers use [OWASP Java HTML Sanitizer](https://owasp.org/www-project-java-html-sanitizer/) with static singleton `PolicyFactory` instances (thread-safe).

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

Processing runs on the intermediate map representation (after JSON parsing, before DTO materialization). Skip annotations short-circuit the entire chain for that field.

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

- `dev.vertique:core`
- `dev.vertique:rest-core`
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer` (OWASP Java HTML Sanitizer, used by HTML sanitizers)
- `org.projectlombok:lombok` (provided scope)

---

## Version History

| Date | Change |
|------|--------|
| 2026-04-07 | Initial implementation — `SanitizationModule`, `ProcessorResolver` (two-tier resolution); 8 built-in canonicalizers (Trim, NFKC, NFC, NormalizeLineEndings, CollapseWhitespace, UpperCase, LowerCase, RemoveIdentifierSeparators); 5 built-in sanitizers (StripControlChars, StripAllHtml, BasicHtml, LinksHtml, RichTextHtml); `InputObjectProcessor` wired via `@BindsOptionalOf` from `RestModule` |
