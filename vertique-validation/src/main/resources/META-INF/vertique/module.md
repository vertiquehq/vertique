<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Validation Module

> **Status:** Stable
> **Package:** `dev.vertique.validation`
> **Artifact:** `vertique-validation`
> **Depends on:** core

`vertique-validation` is the Hibernate Validator-backed implementation of Jakarta Bean Validation
(JSR 380) for Vertique. The HTTP-agnostic API — `BeanValidator`, `ViolationDetail`,
`ParameterViolation`, `BeanValidationException`, `@ValidateWith`, and the character-policy types —
lives in `dev.vertique:vertique-core`; this artifact supplies the validator factory, the
Dagger-aware `ConstraintValidatorFactory`, the violation-classification pipeline, and the
`@AllowedCharacters` constraint family.

Installing `ValidationModule` does two things: it makes `BeanValidator` injectable anywhere, and it
turns on automatic method-parameter validation for JAX-RS resource methods and WebSocket
`@OnMessage` handlers. There is no per-method opt-in and no configuration section.

---

## When To Use It

Install `ValidationModule` whenever an application declares Bean Validation constraints
(`@NotNull`, `@Size`, `@Pattern`, …) on request DTOs, resource-method parameters, or domain objects
it validates programmatically.

| Pairing | Effect |
|---|---|
| `dev.vertique:vertique-rest-jaxrs` | Resource-method parameters are validated on every invocation; violations become HTTP 400 problem details with per-parameter location context |
| `dev.vertique:vertique-rest-websocket` | Decoded `@OnMessage` payloads are validated with the endpoint's `@ValidateWith` groups |
| any module | `BeanValidator` is injectable for service-layer and event-bus validation |

Without this module the framework compiles and runs; `BeanValidator` is simply absent and REST
parameter validation is silently skipped.

---

## Core Concepts

**Two entry points, one engine.** `BeanValidator` is the programmatic API. The REST and WebSocket
layers call the same interface — they do not talk to Jakarta `Validator` directly — so a constraint
behaves identically whether it fires on a request parameter or on an object you validate by hand.

**Violations are structured, never raw.** Every violation is a `ViolationDetail`: property path,
interpolated message, a classified `type` string, and an `args` map carrying the constraint's own
parameters. The invalid value is deliberately excluded so passwords, tokens, and PII cannot leak
into an error response or a log line.

**Validation groups come from the call site or the annotation.** Programmatic callers pass groups
to `validate`/`check`; REST and WebSocket handlers declare them with `@ValidateWith` on the method.
Absent groups means the Jakarta default group.

**Validation is unconditional once installed.** With `ValidationModule` on the component, the JAX-RS
invoker calls `BeanValidator.checkParameters(...)` before *every* resource-method invocation,
including methods that carry no constraint annotations at all. Hibernate Validator caches the
executable's constraint metadata after the first call, so the steady-state per-request cost is a
metadata cache lookup rather than a re-analysis of the method.

```java
@Singleton
public class OrderService {

    private final BeanValidator validator;

    @Inject
    public OrderService(BeanValidator validator) {
        this.validator = validator;
    }

    public void processOrder(OrderRequest request) {
        validator.validate(request); // throws BeanValidationException if invalid
    }

    public List<ViolationDetail> preCheck(OrderRequest request) {
        return validator.check(request); // non-throwing; empty list when valid
    }
}
```

---

## Key Classes

### `BeanValidator`

`dev.vertique.core.validation.BeanValidator` — the programmatic validation API. Injectable as a
`@Singleton` once `ValidationModule` is installed.

```java
public interface BeanValidator {

    // Throwing — raise BeanValidationException when invalid
    <T> void validate(T object);
    <T> void validate(T object, Class<?>... groups);

    // Non-throwing — return the violations; empty list when valid
    <T> List<ViolationDetail> check(T object);
    <T> List<ViolationDetail> check(T object, Class<?>... groups);

    // Method-parameter validation, carrying the parameter index
    List<ParameterViolation> checkParameters(
            Object instance, Method method, Object[] args, Class<?>... groups);

    void validateParameters(
            Object instance, Method method, Object[] args, Class<?>... groups);
}
```

All six methods reject a `null` target, `method`, or `args` with `NullPointerException` — a `null`
object is a programming error, not a validation failure.

**Validation groups:**

```java
public interface Create {}
public interface Update {}

public record UserRequest(
        @NotNull(groups = Create.class) String name,
        @NotNull Long id) {}

validator.validate(request, Create.class);
```

### `ViolationDetail`

`dev.vertique.core.validation.ViolationDetail` — the HTTP-agnostic violation record.

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViolationDetail(
        String path,                        // "name", "address.city"
        String message,                     // interpolated constraint message
        @Nullable String type,              // classification, e.g. "required", "size"
        @Nullable Map<String, Object> args  // e.g. {min: 1, max: 100}
) {
    public static ViolationDetail of(String path, String message) { ... }
}
```

### `ParameterViolation`

`dev.vertique.core.validation.ParameterViolation` pairs a `ViolationDetail` with the zero-based
index of the offending method parameter, which is what lets the REST layer map a violation onto an
HTTP location.

```java
public record ParameterViolation(int parameterIndex, ViolationDetail detail) {}
```

`parameterIndex` is `-1` when the index could not be determined from the violation path.

### `BeanValidationException`

`dev.vertique.core.validation.BeanValidationException` extends
`dev.vertique.core.exception.ValidationException`, so the REST error pipeline renders it as HTTP 400.
Thrown by `validate(...)` and `validateParameters(...)`.

```java
public class BeanValidationException extends ValidationException {
    public BeanValidationException(String message, List<ViolationDetail> violations) { ... }
    public BeanValidationException(String message, List<ViolationDetail> violations, Throwable cause) { ... }
    public List<ViolationDetail> violations() { ... } // unmodifiable, never null
}
```

The violation list is defensively copied; a `null` list becomes an empty one.

### `@ValidateWith`

`dev.vertique.core.validation.ValidateWith` selects the validation groups for a method. Place it on
a JAX-RS resource method or a WebSocket `@OnMessage` method; an empty array means the default group.

```java
@POST @Path("/users")
@ValidateWith({Create.class})
public Future<User> createUser(@Valid CreateUserRequest request) { ... }

@PUT @Path("/users/{id}")
@ValidateWith({Update.class})
public Future<User> updateUser(@PathParam("id") UUID id, @Valid UpdateUserRequest request) { ... }
```

The JAX-RS scanner also honours `@ValidateWith` declared on a resource *interface* method, so a
contract interface can pin the groups for all its implementations.

### Invariants & Gotchas

- A Dagger-managed `ConstraintValidator` (extension tier 1, below) is a **shared singleton**. It must
  be stateless and must not depend on `initialize()` to capture per-annotation state — every use of
  the annotation sees the same instance. Validators that need annotation parameters (`@Size` with
  varying `min`/`max`) belong on the reflection tier.
- Two Dagger-contributed `ConstraintValidator` instances of the same concrete class fail component
  construction with `IllegalStateException: Duplicate ConstraintValidator class: …`.
- `ViolationDetail.args()` and `type()` may be `null`; both are omitted from JSON.

---

## Character Policy Validation

### `@AllowedCharacters`

`dev.vertique.validation.constraints.AllowedCharacters` validates that a string contains only the
characters a `CharacterPolicy` permits. It is repeatable (multiple constraints on one element are
AND-ed) and targets types, fields, record components, parameters, type uses, and annotation types.

```java
public record CreateProjectRequest(
        @AllowedCharacters(policy = IdentifierPolicy.class) String name,
        @AllowedCharacters(policy = SlugPolicy.class) String slug,
        @AllowedCharacters(policy = PersonNamePolicy.class) String ownerName) {}
```

`null` passes; pair with `@NotNull` or `@NotBlank` to require a value. Violations classify as
`allowed_characters` and carry `{policy: "<PolicySimpleName>"}` in `args`.

On a **field or parameter** the constraint validates that one `CharSequence`. On a **type** it
traverses the object graph and applies the policy to every reachable `String`, including strings
inside `Collection` and `Map` values and inside nested application DTOs. Traversal is cycle-safe
(identity-based visited set) and skips JDK types, primitives, boxed primitives, enums, and arrays.

Object-level traversal skips an element that carries its own `@AllowedCharacters` (it validates
itself at field level) or `dev.vertique.core.validation.SkipAllowedCharacters`. Static fields are
never traversed. Meta-annotations composed from either annotation are recognised.

The `InputValueContext` handed to a policy from this constraint is synthetic: `location()` is
always `BODY` and `ownerType()` is always `Object.class` (object traversal does supply a real
`path`). Bean Validation has no accurate provenance for a value, so a policy must not branch on
`location()` — see the `vertique-core` reference for the full contract.

```java
@AllowedCharacters(policy = UnicodeCommonTextPolicy.class)
public record Article(
        String title,
        String body,
        @SkipAllowedCharacters String rawHtml,               // exempt
        @AllowedCharacters(policy = SlugPolicy.class) String slug) {} // own policy wins
```

### `CharacterPolicy`

`dev.vertique.core.validation.CharacterPolicy` is the policy SPI.

```java
public interface CharacterPolicy {
    CharacterPolicyResult validate(String value, InputValueContext context);
}
```

Implementations must be stateless, thread-safe, deterministic, and must treat `null` as valid.
Return `CharacterPolicyResult.passed()` or
`CharacterPolicyResult.failed(index, codePoint, reason)`; the record's accessors are `valid()`,
`invalidIndex()`, `invalidCodePoint()`, and `reason()`.

`InputValueContext` (`dev.vertique.core.sanitization`) is
`(InputLocation location, String path, String logicalName, Class<?> ownerType)`. Both built-in
validators pass `InputLocation.BODY`; the object-level validator sets `path`/`logicalName` to the
traversed field path, the field-level validator passes empty strings.

### Built-in policies

All live in `dev.vertique.validation.constraints` and have a public no-arg constructor.

| Policy | Accepts |
|---|---|
| `IdentifierPolicy` | Unicode letters, Unicode digits, `_`, `-`, `.` |
| `SlugPolicy` | ASCII `a`–`z`, `0`–`9`, `-` |
| `PersonNamePolicy` | Unicode letters (any script), space, `'`, `-`, `.`, `,` |
| `AddressLinePolicy` | Unicode letters and digits, space, and `. , - ' / # ( ) &` |
| `UnicodeCommonTextPolicy` | Unicode categories L\*, N\*, Z\*, P\* plus an ASCII punctuation allowlist; rejects ISO control characters and emoji |
| `UnicodePrintableTextPolicy` | Everything except ISO control characters |
| `UnicodePrintableNoEmojiTextPolicy` | Everything except ISO control characters and emoji |

"Emoji" here means a code point in the `So` (other symbol) category at or above `U+2600`, or any
code point in `U+1F000`–`U+1FFFF`.

### Composed annotations

| Annotation | Composes | Classified as |
|---|---|---|
| `@PersonName` | `@AllowedCharacters(policy = PersonNamePolicy.class)` | `allowed_characters` |
| `@AddressLine` | `@AllowedCharacters(policy = AddressLinePolicy.class)` | `allowed_characters` |
| `@UnicodePrintableNoEmojiText` | `@AllowedCharacters(policy = UnicodePrintableNoEmojiTextPolicy.class)` | `allowed_characters` |
| `@DigitsOnly` | `@Pattern(regexp = "[0-9]+")` | `pattern` |
| `@AlphaNumeric` | `@Pattern(regexp = "[a-zA-Z0-9]+")` | `pattern` |

`@DigitsOnly` and `@AlphaNumeric` are **not** character-policy constraints — they are regex
constraints, so they reject the empty string (`+` requires at least one character) and their
violations carry `{regexp: …}` rather than `{policy: …}`. All five accept `null`.

```java
public record CreateUserRequest(
        @PersonName String firstName,
        @PersonName String lastName,
        @AddressLine String streetAddress,
        @UnicodePrintableNoEmojiText String bio) {}
```

### Policies with injected dependencies

A policy with a public no-arg constructor needs no registration. A policy that needs Dagger
dependencies must be contributed as a `CharacterPolicyBinding`, keyed by its concrete class:

```java
@Singleton
public class ForbiddenWordsPolicy implements CharacterPolicy {

    private final ForbiddenWordRepository repo;

    @Inject
    public ForbiddenWordsPolicy(ForbiddenWordRepository repo) {
        this.repo = repo;
    }

    @Override
    public CharacterPolicyResult validate(String value, InputValueContext context) {
        int idx = repo.firstForbiddenIndex(value);
        return idx < 0
                ? CharacterPolicyResult.passed()
                : CharacterPolicyResult.failed(idx, value.codePointAt(idx), "forbidden word");
    }
}

// In a Dagger module:
@Provides @IntoSet
static CharacterPolicyBinding forbiddenWordsPolicy(ForbiddenWordsPolicy p) {
    return new CharacterPolicyBinding(ForbiddenWordsPolicy.class, p);
}
```

`ValidationModule` wires a `CharacterPolicyResolver` over that multibinding and hands it to the
`@AllowedCharacters` validators. Resolution is Dagger binding first, reflective no-arg constructor
second; when neither works the validator throws
`jakarta.validation.ValidationException: Cannot instantiate CharacterPolicy: …`.

Outside a Dagger context — a bare `Validation.buildDefaultValidatorFactory()`, for example — only
the reflective tier is available, so policies with injected dependencies will not resolve.

---

## Violation Classification

`type` is resolved by three steps: an exact `ViolationTypeMapping` lookup, then each contributed
`ViolationTypeMapper` in turn, then the constraint annotation's simple name.

**Built-in type mappings:**

| Annotation | `type` |
|---|---|
| `@NotNull`, `@NotBlank`, `@NotEmpty` | `required` |
| `@Null` | `null` |
| `@Size` | `size` |
| `@Min`, `@DecimalMin`, `@Positive`, `@PositiveOrZero` | `min` |
| `@Max`, `@DecimalMax`, `@Negative`, `@NegativeOrZero` | `max` |
| `@Pattern` | `pattern` |
| `@Email` | `email` |
| `@Digits` | `digits` |
| `@Future`, `@FutureOrPresent`, `@Past`, `@PastOrPresent` | `date` |
| `@AssertTrue` | `assert_true` |
| `@AssertFalse` | `assert_false` |
| `@Length`, `@CodePointLength` (Hibernate) | `length` |
| `@Range` (Hibernate) | `range` |
| `@UniqueElements` (Hibernate) | `unique_elements` |
| `@URL` (Hibernate) | `url` |
| `@AllowedCharacters` | `allowed_characters` |

A contributed `ViolationTypeMapping` for an annotation already in the table replaces the built-in
one (last-wins merge).

`args` is resolved by the first contributed `ViolationArgsInspector` whose `supports(...)` returns
`true`, otherwise by the built-in inspector.

**Built-in args extraction:**

| Annotation | `args` |
|---|---|
| `@Size` | `{min, max}` |
| `@Min` / `@Max` | `{value}` |
| `@DecimalMin` / `@DecimalMax` | `{value, inclusive}` |
| `@Positive` | `{min: 0, inclusive: false}` |
| `@PositiveOrZero` | `{min: 0, inclusive: true}` |
| `@Negative` | `{max: 0, inclusive: false}` |
| `@NegativeOrZero` | `{max: 0, inclusive: true}` |
| `@Digits` | `{integer, fraction}` |
| `@Pattern` | `{regexp}` |
| `@Email` | `{regexp}`, omitted when the regexp is the default `.*` |
| `@Length` | `{min, max}` |
| `@CodePointLength` | `{min, max}`, plus `normalizationStrategy` when it is not `NONE` |
| `@Range` | `{min, max}` |
| `@AllowedCharacters` | `{policy}` |
| anything else | `null` |

---

## JAX-RS Integration

`vertique-rest-jaxrs` declares `@BindsOptionalOf BeanValidator`. `ValidationModule` provides that
binding, so simply listing it on the component activates method-parameter validation:

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    RestModule.class,
    ValidationModule.class, // BeanValidator + automatic REST method validation
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

Per request, the resource-method invoker extracts the arguments, calls
`BeanValidator.checkParameters(...)` with the method's `@ValidateWith` groups, and — when the list
is non-empty — throws `RestValidationException`. Each `ParameterViolation` is enriched with an HTTP
location taken from the parameter's JAX-RS source (`body`, `query`, `path`, `header`, `cookie`,
`form`); for a `@BeanParam`, the violated field's own JAX-RS annotation supplies both the location
and the reported parameter name. The error pipeline renders the exception as HTTP 400 with a
`ValidationProblemDetail` body:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed",
  "errors": [
    { "path": "name", "detail": "must not be blank", "location": "body", "type": "required" },
    { "path": "page", "detail": "must be greater than 0", "location": "query", "type": "min" }
  ]
}
```

A `BeanValidationException` thrown from your own code — `validator.validate(dto)` inside a resource
method or service — also renders as 400 with a `ValidationProblemDetail`, but its entries carry no
`location`, because a programmatic validation has no parameter to attribute.

For WebSocket endpoints, `vertique-rest-websocket` validates the decoded `@OnMessage` payload with
`BeanValidator.validate(decoded, groups)` when the method declares `@ValidateWith`.

---

## Extension Points

All six extension points are Dagger `Set<…>` multibindings declared by `ValidationModule`.

### `Set<ConstraintValidator<?, ?>>`

Individual Dagger-managed validators, resolved by concrete class. **Must be stateless** — the same
instance is shared across every use of the annotation.

```java
@Singleton
public class UniqueEmailValidator implements ConstraintValidator<UniqueEmail, String> {

    private final UserRepository users;

    @Inject
    public UniqueEmailValidator(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean isValid(String email, ConstraintValidatorContext context) {
        return email == null || !users.existsByEmail(email);
    }
}

@Provides @IntoSet
static ConstraintValidator<?, ?> uniqueEmailValidator(UniqueEmailValidator v) {
    return v;
}
```

### `Set<ConstraintValidatorFactory>`

Additional factories, consulted in turn after the Dagger-managed set. The first factory returning a
non-`null` instance wins; a factory that throws is skipped. Use this to register a batch of
third-party library validators.

```java
@Provides @IntoSet
static ConstraintValidatorFactory libraryValidators() {
    return new SomeLibraryConstraintValidatorFactory();
}
```

Standard Hibernate Validator built-ins (`@NotNull`, `@Size`, `@Min`, `@Pattern`, …) need no
registration at all — they resolve through the final reflective tier, which creates a fresh instance
per `getInstance(...)` call from the validator class's no-arg constructor so Hibernate Validator can
call `initialize()` per annotation descriptor.

### `Set<ViolationTypeMapping>`

Annotation-to-`type`-string bindings, merged over the built-ins.

```java
@Provides @IntoSet
static ViolationTypeMapping uniqueEmailMapping() {
    return new ViolationTypeMapping(UniqueEmail.class, "unique_email");
}
```

### `Set<ViolationTypeMapper>`

Programmatic classification when a per-annotation binding is impractical. Return `null` to pass the
annotation on to the next mapper.

```java
public interface ViolationTypeMapper {
    @Nullable String typeFor(Class<? extends Annotation> constraintAnnotation);
}

@Singleton
public class AcmeViolationTypeMapper implements ViolationTypeMapper {

    @Inject
    public AcmeViolationTypeMapper() {}

    @Override
    public String typeFor(Class<? extends Annotation> constraintAnnotation) {
        return constraintAnnotation.getPackageName().startsWith("com.acme.validation.constraints")
                ? "acme_" + constraintAnnotation.getSimpleName().toLowerCase(Locale.ROOT)
                : null;
    }
}

@Provides @IntoSet
static ViolationTypeMapper acmeMapper(AcmeViolationTypeMapper mapper) {
    return mapper;
}
```

### `Set<ViolationArgsInspector>`

Custom `args` extraction. The first inspector whose `supports(...)` returns `true` is used — and its
`extract(...)` result is final, even when it returns `null`.

```java
public interface ViolationArgsInspector {
    boolean supports(Class<? extends Annotation> constraintAnnotation);
    @Nullable Map<String, Object> extract(ConstraintViolation<?> violation);
}

@Singleton
public class AllowedValuesArgsInspector implements ViolationArgsInspector {

    @Inject
    public AllowedValuesArgsInspector() {}

    @Override
    public boolean supports(Class<? extends Annotation> constraintAnnotation) {
        return constraintAnnotation == AllowedValues.class;
    }

    @Override
    public Map<String, Object> extract(ConstraintViolation<?> violation) {
        AllowedValues ann = (AllowedValues) violation.getConstraintDescriptor().getAnnotation();
        return Map.of("allowed", Arrays.asList(ann.value()));
    }
}

@Provides @IntoSet
static ViolationArgsInspector allowedValuesInspector(AllowedValuesArgsInspector inspector) {
    return inspector;
}
```

### `Set<CharacterPolicyBinding>`

Dagger-managed `CharacterPolicy` instances — see
[Policies with injected dependencies](#policies-with-injected-dependencies).

---

## Module Dagger Bindings

```java
@Module
public abstract class ValidationModule {
    @Multibinds abstract Set<ConstraintValidator<?, ?>> constraintValidators();
    @Multibinds abstract Set<ConstraintValidatorFactory> constraintValidatorFactories();
    @Multibinds abstract Set<ViolationTypeMapping> violationTypeMappings();
    @Multibinds abstract Set<ViolationTypeMapper> violationTypeMappers();
    @Multibinds abstract Set<ViolationArgsInspector> violationArgsInspectors();
    @Multibinds abstract Set<CharacterPolicyBinding> characterPolicyBindings();
}
```

| Binding | Scope | Notes |
|---|---|---|
| `ValidatorFactory` | `@Singleton` | Built from the default provider, configured with the Dagger-aware `ConstraintValidatorFactory` |
| `Validator` | `@Singleton` | `ValidatorFactory.getValidator()` |
| `BeanValidator` | `@Singleton` | Backed by `DefaultBeanValidator` |
| `CharacterPolicyResolver` | `@Singleton` | Backed by `Set<CharacterPolicyBinding>` |
| `Set<ViolationTypeMapping>` | multibinding | Seeded with every built-in mapping in the table above |
| `Set<ConstraintValidatorFactory>` | multibinding | Seeded with the `@AllowedCharacters` factory that injects `CharacterPolicyResolver` |
| `Set<ViolationArgsInspector>` | multibinding | Seeded with the `@AllowedCharacters` policy-name inspector |

The module takes no constructor arguments and reads no configuration section.

---

## Failures, Constraints, and Common Mistakes

| Symptom | Cause |
|---|---|
| `IllegalStateException: Duplicate ConstraintValidator class: …` at startup | Two `@IntoSet ConstraintValidator` contributions of the same concrete class |
| `jakarta.validation.ValidationException: Cannot instantiate ConstraintValidator: …` | The validator class reached the reflective tier but has no accessible no-arg constructor, and no contributed factory handled it |
| `jakarta.validation.ValidationException: Cannot instantiate CharacterPolicy: …` | The `@AllowedCharacters` policy has no no-arg constructor and no `CharacterPolicyBinding` was contributed |
| Constraints silently never fire on resource methods | `ValidationModule` is not on the Dagger component, so the optional `BeanValidator` binding is empty |
| A DI-backed validator sees stale annotation parameters | It is a tier-1 singleton relying on `initialize()`; move it to the reflective tier or make it stateless |
| Empty string rejected by `@DigitsOnly` / `@AlphaNumeric` | They compose `@Pattern` with a `+` quantifier, so `""` fails; use a character policy if the empty string must pass |
| Object-level `@AllowedCharacters` skips a field | The field carries its own `@AllowedCharacters` or `@SkipAllowedCharacters`, or is `static` |

---

## Dependencies

| Dependency | Why |
|---|---|
| `dev.vertique:vertique-core` | `BeanValidator`, `ViolationDetail`, `ParameterViolation`, `BeanValidationException`, `@ValidateWith`, `CharacterPolicy` and friends, `InputValueContext` |
| `jakarta.validation:jakarta.validation-api` | The Bean Validation API this module implements against |
| `org.hibernate.validator:hibernate-validator` | The validation engine backing `ValidatorFactory` |
| `org.glassfish.expressly:expressly` | EL implementation Hibernate Validator requires for message interpolation |
| `com.google.dagger:dagger` | `ValidationModule` and the multibinding extension points |
| `jakarta.inject:jakarta.inject-api` | `@Inject` / `@Singleton` |
| `jakarta.annotation:jakarta.annotation-api` | `@Nullable` on SPI signatures |
| `org.slf4j:slf4j-api` | Diagnostic logging in the validator factory |
| `org.projectlombok:lombok` | Compile-time only |
