<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Validation Module

> **Status:** Implemented
> **Package:** `dev.vertique.validation`
> **Artifact:** `validation`
> **Depends on:** core

Jakarta Bean Validation (JSR 380) integration with Dagger DI support. Provides a two-layer architecture: the `core` module defines the HTTP-agnostic API interfaces (`BeanValidator`, `ViolationDetail`, `BeanValidationException`, `ValidateWith`), and this module provides the Hibernate Validator-backed implementation with full Dagger DI integration. REST method parameter validation is automatic when `ValidationModule` is present in the Dagger component.

---

## Package Layout

| Package | Contents |
|---------|----------|
| `dev.vertique.core.validation` | `BeanValidator`, `ViolationDetail`, `BeanValidationException`, `ParameterViolation`, `ValidateWith` — HTTP-agnostic API (in `core`); `CharacterPolicy`, `CharacterPolicyResult`, `CharacterPolicyBinding`, `@SkipAllowedCharacters` |
| `dev.vertique.validation` | `ValidationModule`, `DefaultBeanValidator`, `DaggerConstraintValidatorFactory`, `ViolationDetailMapper`, `ViolationTypeMapping`, `ViolationTypeMapper`, `ViolationArgsInspector`, `BuiltInViolationArgsInspector`, `AllowedCharactersValidatorFactory`, `AllowedCharactersArgsInspector` |
| `dev.vertique.validation.constraints` | `@AllowedCharacters` (repeatable), `AllowedCharactersValidator`, `AllowedCharactersObjectValidator`, `CharacterPolicyResolver`; 7 built-in policies; 5 composed annotations |

---

## Key Classes

### BeanValidator (core)

Programmatic validation API in `dev.vertique.core.validation`. Usable in service layers, event bus handlers, and any non-HTTP context.

```java
public interface BeanValidator {

    // Throwing variants — throw BeanValidationException if invalid
    <T> void validate(T object);
    <T> void validate(T object, Class<?>... groups);

    // Non-throwing variants — return violations list; empty if valid
    <T> List<ViolationDetail> check(T object);
    <T> List<ViolationDetail> check(T object, Class<?>... groups);

    // Method parameter validation — returns structured ParameterViolation list
    List<ParameterViolation> checkParameters(Object instance, Method method, Object[] args, Class<?>... groups);

    // Throwing method parameter validation — throws BeanValidationException
    void validateParameters(Object instance, Method method, Object[] args, Class<?>... groups);
}
```

**Programmatic validation:**

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
        // ... business logic
    }

    public List<ViolationDetail> preCheck(OrderRequest request) {
        return validator.check(request); // non-throwing; empty list if valid
    }
}
```

**Validation groups:**

```java
public interface Create {}
public interface Update {}

public record UserRequest(
    @NotNull(groups = Create.class) String name,
    @NotNull Long id
) {}

// Apply group at call site:
validator.validate(request, Create.class);
```

---

### ViolationDetail (core)

HTTP-agnostic violation record in `dev.vertique.core.validation`.

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViolationDetail(
    String path,        // property path (e.g., "name", "address.city")
    String message,     // interpolated constraint message
    @Nullable String type,              // classified type (e.g., "required", "size")
    @Nullable Map<String, Object> args  // constraint arguments (e.g., {min: 1, max: 100})
) {
    public static ViolationDetail of(String path, String message) { ... }
}
```

The invalid value is intentionally excluded to prevent accidental leakage of sensitive data (passwords, tokens, PII) in error responses or logs.

---

### BeanValidationException (core)

Thrown by `BeanValidator.validate()` and `BeanValidator.validateParameters()`. Extends `ValidationException` from `core.exception`, which maps to HTTP 400 by the REST error pipeline.

```java
public class BeanValidationException extends ValidationException {
    public BeanValidationException(String message, List<ViolationDetail> violations) { ... }
    public BeanValidationException(String message, List<ViolationDetail> violations, Throwable cause) { ... }
    public List<ViolationDetail> violations() { ... }  // unmodifiable list
}
```

---

### ParameterViolation (core)

Associates a `ViolationDetail` with the zero-based parameter index of the violated method parameter. Used by `BeanValidator.checkParameters()` to enable REST layer mapping of violations to HTTP locations (query, header, body, etc.).

```java
public record ParameterViolation(
    int parameterIndex,  // zero-based; -1 if index could not be determined
    ViolationDetail detail
) {}
```

---

### ValidateWith (core)

Method-level annotation for specifying validation groups. Place on JAX-RS resource methods or event bus service methods to control which validation groups are active.

```java
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidateWith {
    Class<?>[] value();  // validation group classes; empty = default group
}
```

**Usage:**

```java
@POST @Path("/users")
@ValidateWith({Create.class})
public Future<User> createUser(@Valid CreateUserRequest request) { ... }

@PUT @Path("/users/{id}")
@ValidateWith({Update.class})
public Future<User> updateUser(@PathParam("id") UUID id, @Valid UpdateUserRequest request) { ... }
```

---

### DefaultBeanValidator

`@Singleton` implementation of `BeanValidator` backed by Jakarta `Validator` and `ViolationDetailMapper`.

```java
@Singleton
public class DefaultBeanValidator implements BeanValidator {
    @Inject
    DefaultBeanValidator(Validator validator, ViolationDetailMapper detailMapper) { ... }
}
```

For method parameter validation, uses `ExecutableValidator` to walk the constraint violation `Path` and extract the parameter index from the `PARAMETER` node plus the property path from subsequent `PROPERTY` nodes.

---

### DaggerConstraintValidatorFactory

`@Singleton` `ConstraintValidatorFactory` with a three-tier resolution chain. Wired automatically by `ValidationModule` into the Jakarta `ValidatorFactory`.

**Resolution order:**

| Tier | Source | Notes |
|------|--------|-------|
| 1 | `Set<ConstraintValidator<?,?>>` multibinding | Dagger-managed singletons. Must be stateless — the same instance is shared across all uses of the constraint annotation. |
| 2 | `Set<ConstraintValidatorFactory>` multibinding | Contributed factories; useful for library validators. First factory returning non-null wins. |
| 3 | Reflection (no-arg constructor) | Handles all standard Hibernate Validator built-ins. A fresh instance per `getInstance()` call so Hibernate Validator can call `initialize()` per annotation descriptor. |

**Stateless requirement for Tier 1:** Dagger-managed validators must not rely on `initialize()` to configure per-annotation state, since the same instance is shared across all uses. Validators requiring annotation-parameter state (e.g., `@Size` with variable `min`/`max`) resolve via the reflection fallback and are not registered via Dagger multibinding.

---

### ViolationDetailMapper

Package-private `@Singleton` that converts `ConstraintViolation` sets into `ViolationDetail` records by resolving type strings and extracting constraint arguments.

**Type resolution chain:**

| Step | Mechanism | Example |
|------|-----------|---------|
| 1 | `Set<ViolationTypeMapping>` — direct annotation → type string lookup | `@NotNull` → `"required"` |
| 2 | `Set<ViolationTypeMapper>` — programmatic mapping (e.g., by package prefix) | custom library constraint → `"library_type"` |
| 3 | Fallback | constraint annotation simple name |

User-contributed `ViolationTypeMapping` entries override built-in defaults (last-wins merge on duplicate annotation type).

**Args resolution chain:**

| Step | Mechanism |
|------|-----------|
| 1 | First `ViolationArgsInspector` whose `supports()` returns `true` |
| 2 | `BuiltInViolationArgsInspector` fallback |

---

### ViolationTypeMapping

Simple record binding a constraint annotation type to a violation type string.

```java
public record ViolationTypeMapping(Class<? extends Annotation> annotationType, String type) {}
```

**Contribute a mapping:**

```java
@Provides @IntoSet
static ViolationTypeMapping uniqueEmailMapping() {
    return new ViolationTypeMapping(UniqueEmail.class, "unique_email");
}
```

**Built-in mappings** (provided by `ValidationModule`):

| Annotation | Type string |
|------------|------------|
| `@NotNull`, `@NotBlank`, `@NotEmpty` | `"required"` |
| `@Null` | `"null"` |
| `@Size` | `"size"` |
| `@Min`, `@DecimalMin`, `@Positive`, `@PositiveOrZero` | `"min"` |
| `@Max`, `@DecimalMax`, `@Negative`, `@NegativeOrZero` | `"max"` |
| `@Pattern` | `"pattern"` |
| `@Email` | `"email"` |
| `@Digits` | `"digits"` |
| `@Future`, `@FutureOrPresent`, `@Past`, `@PastOrPresent` | `"date"` |
| `@AssertTrue` | `"assert_true"` |
| `@AssertFalse` | `"assert_false"` |
| `@Length`, `@CodePointLength` (Hibernate) | `"length"` |
| `@Range` (Hibernate) | `"range"` |
| `@UniqueElements` (Hibernate) | `"unique_elements"` |
| `@URL` (Hibernate) | `"url"` |

---

### ViolationTypeMapper

SPI for programmatic type mapping when a simple `ViolationTypeMapping` is insufficient (e.g., mapping by package prefix or annotation metadata). Return `null` to indicate this mapper does not handle the given annotation.

```java
public interface ViolationTypeMapper {
    @Nullable
    String typeFor(Class<? extends Annotation> constraintAnnotation);
}
```

**Example — map all constraints in a library package:**

```java
@Singleton
public class AcmeViolationTypeMapper implements ViolationTypeMapper {
    @Inject public AcmeViolationTypeMapper() {}

    @Override
    public String typeFor(Class<? extends Annotation> constraintAnnotation) {
        if (constraintAnnotation.getPackageName().startsWith("com.acme.validation.constraints")) {
            return "acme_" + constraintAnnotation.getSimpleName().toLowerCase(Locale.ROOT);
        }
        return null;
    }
}

// Contribute via Dagger:
@Provides @IntoSet
static ViolationTypeMapper acmeMapper(AcmeViolationTypeMapper mapper) { return mapper; }
```

---

### ViolationArgsInspector

SPI for extracting constraint arguments from a `ConstraintViolation`. The first inspector whose `supports()` returns `true` is used; if none match, `BuiltInViolationArgsInspector` handles standard Jakarta and Hibernate constraints.

```java
public interface ViolationArgsInspector {
    boolean supports(Class<? extends Annotation> constraintAnnotation);
    @Nullable Map<String, Object> extract(ConstraintViolation<?> violation);
}
```

**Built-in args extraction** (`BuiltInViolationArgsInspector`):

| Annotation | Extracted args |
|------------|---------------|
| `@Size` | `{min, max}` |
| `@Min` / `@Max` | `{value}` |
| `@DecimalMin` / `@DecimalMax` | `{value, inclusive}` |
| `@Positive` | `{min: 0, inclusive: false}` |
| `@PositiveOrZero` | `{min: 0, inclusive: true}` |
| `@Negative` | `{max: 0, inclusive: false}` |
| `@NegativeOrZero` | `{max: 0, inclusive: true}` |
| `@Digits` | `{integer, fraction}` |
| `@Pattern` | `{regexp}` |
| `@Email` (non-default regexp only) | `{regexp}` |
| `@Length` / `@CodePointLength` | `{min, max}` (+ `normalizationStrategy` if set) |
| `@Range` | `{min, max}` |
| Everything else | `null` (no meaningful args) |

**Custom args inspector example:**

```java
@Singleton
public class AllowedValuesArgsInspector implements ViolationArgsInspector {
    @Inject public AllowedValuesArgsInspector() {}

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

// Contribute via Dagger:
@Provides @IntoSet
static ViolationArgsInspector allowedValuesInspector(AllowedValuesArgsInspector inspector) {
    return inspector;
}
```

---

## Character Policy Validation

### @AllowedCharacters

Repeatable Bean Validation constraint that validates a string contains only characters permitted by the specified `CharacterPolicy`. Applicable to fields, record components, parameters, type uses, and annotation types (for composed constraints).

```java
public record CreateProjectRequest(
    @AllowedCharacters(policy = IdentifierPolicy.class)
    String name,

    @AllowedCharacters(policy = SlugPolicy.class)
    String slug,

    @AllowedCharacters(policy = PersonNamePolicy.class)
    String ownerName
) {}
```

Null values pass validation; pair with `@NotNull` or `@NotBlank` to require a value.

When `ValidationModule` is included and `AllowedCharactersValidatorFactory` is registered (automatic), `@AllowedCharacters` constraints work with both field-level and object-level (recursive) validation.

**Type string:** `@AllowedCharacters` violations are mapped to the type string `"allowed_characters"` by `ValidationModule`'s built-in mappings. The `AllowedCharactersArgsInspector` extracts `{policy: "...PolicyClass"}` into the violation's `args` map.

### Built-in Character Policies (`dev.vertique.validation.constraints`)

| Policy | Allowed Characters |
|--------|--------------------|
| `IdentifierPolicy` | Unicode letters, Unicode digits, `_`, `-`, `.` |
| `SlugPolicy` | Lowercase ASCII `a-z`, digits `0-9`, `-` |
| `PersonNamePolicy` | Unicode letters (all scripts), space, `'`, `-`, `.`, `,` |
| `AddressLinePolicy` | Unicode letters and digits, space, common address punctuation (`,-./()#`) |
| `UnicodeCommonTextPolicy` | Unicode letters, digits, punctuation categories (Pd, Pe, Pi, Pf, Po, Ps, Pc), space, common separators |
| `UnicodePrintableTextPolicy` | Any printable Unicode (non-control, non-format, non-surrogate) |
| `UnicodePrintableNoEmojiTextPolicy` | Printable Unicode excluding emoji (So and Cn categories) |

### Built-in Composed Annotations

Composed annotations in `dev.vertique.validation.constraints` delegate to `@AllowedCharacters` with a built-in policy, providing more descriptive constraint names:

| Annotation | Equivalent |
|------------|-----------|
| `@DigitsOnly` | `@AllowedCharacters(policy = ...)` for ASCII digits 0-9 |
| `@AlphaNumeric` | `@AllowedCharacters(policy = ...)` for ASCII letters + digits |
| `@PersonName` | `@AllowedCharacters(policy = PersonNamePolicy.class)` |
| `@AddressLine` | `@AllowedCharacters(policy = AddressLinePolicy.class)` |
| `@UnicodePrintableNoEmojiText` | `@AllowedCharacters(policy = UnicodePrintableNoEmojiTextPolicy.class)` |

```java
public record CreateUserRequest(
    @PersonName String firstName,
    @PersonName String lastName,
    @AddressLine String streetAddress,
    @UnicodePrintableNoEmojiText String bio
) {}
```

### CharacterPolicyResolver

`@Singleton` that resolves `CharacterPolicy` instances through a two-tier chain.

| Tier | Mechanism |
|------|-----------|
| 1 | Dagger multibinding (`Set<CharacterPolicyBinding>`) — returns singleton for policies with injected dependencies |
| 2 | Reflection (`getDeclaredConstructor().newInstance()`) — handles stateless built-in policies |

The resolver is provided by `ValidationModule` and injected into `AllowedCharactersValidatorFactory`.

**Contributing a custom policy with DI:**

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
        // ... check against repo ...
        return CharacterPolicyResult.passed();
    }
}

// Register in a Dagger module:
@Provides @IntoSet
static CharacterPolicyBinding forbiddenWordsPolicy(ForbiddenWordsPolicy p) {
    return new CharacterPolicyBinding(ForbiddenWordsPolicy.class, p);
}
```

### AllowedCharactersValidatorFactory

Package-private `ConstraintValidatorFactory` contributed to the `Set<ConstraintValidatorFactory>` multibinding by `ValidationModule`. Creates `AllowedCharactersValidator` and `AllowedCharactersObjectValidator` instances with the `CharacterPolicyResolver` injected, enabling Dagger-managed policies with dependencies.

Without this factory, `@AllowedCharacters` validators fall through to the reflection tier and only stateless policies (public no-arg constructor) work.

---

## Three Ways to Contribute Custom Validators

### 1. Individual validator with DI

For app-specific validators needing injected dependencies (e.g., database-backed uniqueness checks). The validator must be stateless — the same Dagger-managed singleton is reused for every validation call.

```java
@Singleton
public class UniqueEmailValidator implements ConstraintValidator<UniqueEmail, String> {
    private final UserRepository userRepository;

    @Inject
    public UniqueEmailValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean isValid(String email, ConstraintValidatorContext context) {
        return email == null || !userRepository.existsByEmail(email);
    }
}

// Register in a Dagger module:
@Provides @IntoSet
static ConstraintValidator<?, ?> uniqueEmailValidator(UniqueEmailValidator v) { return v; }
```

### 2. Factory contribution

For registering a batch of validators from a third-party library.

```java
@Provides @IntoSet
static ConstraintValidatorFactory someLibraryValidators() {
    return new SomeLibraryConstraintValidatorFactory();
}
```

### 3. Automatic (reflection fallback)

Standard Hibernate Validator built-in validators (`@NotNull`, `@Size`, `@Min`, `@Pattern`, etc.) resolve automatically via the reflection fallback. No registration needed.

---

## JAX-RS Integration

When `ValidationModule` is included in the Dagger component, REST method parameter validation activates automatically. No per-method opt-in is required.

**How it works:**

1. `RestModule` declares `@BindsOptionalOf jakarta.validation.Validator`
2. `ValidationModule` provides a `Validator` singleton — `Optional<Validator>` resolves to `Optional.of(validator)`
3. `JaxRsRouterMount.Factory` receives `Optional<Validator>` and passes it to `JaxRsRouteRegistrar`
4. `ResourceMethodInvoker` calls `ExecutableValidator.validateParameters()` after argument extraction, before method invocation
5. `ConstraintViolationMapper` maps violations to `RestValidationException` with HTTP location context (`body`, `query`, `path`, `header`, `cookie`, `form`)
6. `DefaultExceptionMapper` maps `RestValidationException` → 400 with `ValidationProblemDetail`

**Example response for a validation failure:**

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

**Enabling validation:**

```java
@Singleton
@Component(modules = {
    VertxModule.class,
    RestModule.class,
    ValidationModule.class,  // enables BeanValidator + automatic REST method validation
    AppModule.class,
    ResourceModule.class
})
interface AppComponent {
    HttpVerticle httpVerticle();
}
```

---

## Extension Points

### `Set<ConstraintValidator<?, ?>>`

Individual Dagger-managed validators. Looked up by concrete class in `DaggerConstraintValidatorFactory` (tier 1). Must be stateless.

```java
@Provides @IntoSet
static ConstraintValidator<?, ?> myValidator(MyConstraintValidator v) { return v; }
```

### `Set<ConstraintValidatorFactory>`

Additional factories for library validators (tier 2 in `DaggerConstraintValidatorFactory`).

```java
@Provides @IntoSet
static ConstraintValidatorFactory libraryFactory() { return new LibraryValidatorFactory(); }
```

### `Set<ViolationTypeMapping>`

Simple annotation-to-type-string mappings. Merged into the built-in map; user contributions override built-ins for the same annotation.

```java
@Provides @IntoSet
static ViolationTypeMapping myConstraintMapping() {
    return new ViolationTypeMapping(MyConstraint.class, "my_constraint_type");
}
```

### `Set<ViolationTypeMapper>`

Programmatic type resolution for complex cases (e.g., by package prefix). Return `null` to pass to the next mapper.

```java
@Provides @IntoSet
static ViolationTypeMapper myTypeMapper(MyViolationTypeMapper mapper) { return mapper; }
```

### `Set<ViolationArgsInspector>`

Custom constraint argument extraction. First inspector whose `supports()` returns `true` wins.

```java
@Provides @IntoSet
static ViolationArgsInspector myArgsInspector(MyViolationArgsInspector inspector) { return inspector; }
```

### `Set<CharacterPolicyBinding>`

Dagger-managed `CharacterPolicy` instances. Looked up by concrete class in `CharacterPolicyResolver` (tier 1). Policies with injected dependencies must be contributed here.

```java
@Provides @IntoSet
static CharacterPolicyBinding myPolicy(MyCharacterPolicy p) {
    return new CharacterPolicyBinding(MyCharacterPolicy.class, p);
}
```

---

## ValidationModule Dagger Bindings

```java
@Module
public abstract class ValidationModule {
    // Multibinding declarations
    @Multibinds abstract Set<ConstraintValidator<?, ?>> constraintValidators();
    @Multibinds abstract Set<ConstraintValidatorFactory> constraintValidatorFactories();
    @Multibinds abstract Set<ViolationTypeMapping> violationTypeMappings();
    @Multibinds abstract Set<ViolationTypeMapper> violationTypeMappers();
    @Multibinds abstract Set<ViolationArgsInspector> violationArgsInspectors();
    @Multibinds abstract Set<CharacterPolicyBinding> characterPolicyBindings();
}
```

| Binding | Type | Notes |
|---------|------|-------|
| `ValidatorFactory` | `@Singleton` | Configured with `DaggerConstraintValidatorFactory` |
| `Validator` | `@Singleton` | From `ValidatorFactory.getValidator()` |
| `BeanValidator` | `@Singleton` | Bound to `DefaultBeanValidator` |
| `CharacterPolicyResolver` | `@Singleton` | Backed by `Set<CharacterPolicyBinding>` multibinding |
| `Set<ViolationTypeMapping>` | Multibinding | Includes built-in Jakarta + Hibernate constraint mappings + `@AllowedCharacters` → `"allowed_characters"` |
| `Set<ConstraintValidatorFactory>` | Multibinding | Includes `AllowedCharactersValidatorFactory` (tier-2 for `@AllowedCharacters` validators) |
| `Set<ViolationArgsInspector>` | Multibinding | Includes `AllowedCharactersArgsInspector` (extracts `{policy: "..."}` from `@AllowedCharacters` violations) |

---

## Dependencies

- `dev.vertique:core`
- `jakarta.validation:jakarta.validation-api`
- `jakarta.inject:jakarta.inject-api`
- `jakarta.annotation:jakarta.annotation-api`
- `com.google.dagger:dagger`
- `org.hibernate.validator:hibernate-validator`
- `org.glassfish.expressly:expressly` (EL implementation required by Hibernate Validator)
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided scope)

---

## Version History

| Date | Change |
|------|--------|
| 2026-03-19 | Initial implementation — `BeanValidator` API in core, `validation` module with Dagger DI, `DaggerConstraintValidatorFactory` three-tier resolution, `ViolationTypeMapping`/`ViolationTypeMapper`/`ViolationArgsInspector` extension points, built-in type mappings and args extraction for standard Jakarta and Hibernate Validator constraints, JAX-RS method parameter validation with HTTP location inference and `@ValidateWith` group support |
| 2026-04-07 | Character policy validation: `CharacterPolicy`/`CharacterPolicyResult`/`CharacterPolicyBinding` in `core.validation`; `@AllowedCharacters` repeatable constraint + `AllowedCharactersValidator` (field-level) + `AllowedCharactersObjectValidator` (object-level recursive) in `validation.constraints`; `CharacterPolicyResolver` two-tier resolution; 7 built-in policies (Identifier, Slug, PersonName, AddressLine, UnicodeCommonText, UnicodePrintableText, UnicodePrintableNoEmojiText); 5 composed annotations (@DigitsOnly, @AlphaNumeric, @PersonName, @AddressLine, @UnicodePrintableNoEmojiText); `AllowedCharactersValidatorFactory` (tier-2 `ConstraintValidatorFactory` for policy DI injection); `AllowedCharactersArgsInspector` (extracts policy class name into violation args) |

---

## Planned Additions

### Validation skipping when no constraints present

`ExecutableValidator.validateParameters()` is called for every resource method invocation when `ValidationModule` is present, even if the method has no validation annotations. Hibernate Validator caches method metadata after the first call, so the per-request cost is a single cache lookup. A future optimization could add a `hasParameterValidation` flag to `ResourceMethodMeta` during route scanning and skip `validateArguments()` when false.

### Return value validation

`@Valid` on method return type — validate response DTOs before serialization. Requires handling async `Future<T>` unwrapping to access the resolved value.

### `Provider`-based DI validators

Change `Set<ConstraintValidator<?,?>>` to `Set<Provider<ConstraintValidator<?,?>>>` so each `getInstance()` call creates a fresh instance with DI support. Enables stateful validators with annotation-parameter configuration (e.g., validators that read `initialize()` state from the annotation descriptor). Requires an API change.

### Validation configuration

Configurable fail-fast mode, custom `MessageInterpolator`, and locale-aware messages via application config.

### Services dispatch validation

Extend `DispatchPipeline` in the `services` module to support Bean Validation on event bus service method parameters. Reuse `BeanValidator` for the programmatic API.

### Cross-parameter validation

Support for `@SupportedValidationTarget(PARAMETERS)` cross-parameter constraints that validate relationships between multiple method parameters.
