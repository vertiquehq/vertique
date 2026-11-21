<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Codegen REST Client Static Proxies Module

> **Status:** Stable

## Overview

`vertique-codegen-rest-client` is an annotation processor that eliminates per-call reflection in the REST client hot path. The bottleneck is `RestClientRequestFactory.extractFieldValue`, which runs on every call that uses `@BeanParam`: it iterates `getRecordComponents()` or walks the class hierarchy via `getDeclaredField()`, calls `setAccessible(true)`, and invokes `Method.invoke()` / `Field.get()` — per call, no caching.

The processor generates two artifact types per compilation unit:

- **`{Client}_RestClientProxy`** — a `public final` class implementing the `@RestClient` interface. It holds `ClientMethodMeta` references in `final` fields (resolved once at construction time from the pre-built `Map<Method, ClientMethodMeta>`) and calls accessor methods directly, with no per-call reflection.
- **`{Bean}_BeanParamAccessor`** — a `public final` class implementing `BeanParamAccessor<T>`. It dispatches over field names via a `switch` expression calling record accessors, public getters, or direct field access — whichever is applicable — with no `setAccessible` or reflective dispatch on the hot path.

Runtime selection is transparent: `RestClientBuilder.build()` and `RestClientFactory.builder()` both look up the generated `_RestClientProxy` companion of the client interface (name rule under "Nested-Class FQN Translation") before falling back to the existing JDK proxy. The application processor boundary described below is the only Maven setup required.

In addition to the performance win, the processor lifts structural validation to compile time: `@Path` placeholder / `@PathParam` mismatches, missing HTTP verb annotations, and non-`Future<T>` return types all surface as build errors.

## Adoption

Applications inheriting `vertique-app-parent` declare `vertique-rest-client` as a runtime
dependency and receive the complete processor facade automatically. Custom-parent applications
import `vertique-bom` and configure only the versionless Dagger and `vertique-codegen-all`
processor paths. See `docs/packaging.md`. The runtime selects generated proxies when present and
retains the JDK proxy as its fallback.

---

## Key Classes

### Processor Options

`RestClientProcessor` is registered automatically via `META-INF/services/javax.annotation.processing.Processor` and recognizes `@RestClient`-annotated interfaces. It supports two options:

```
@SupportedOptions({
    "vertique.codegen.package",
    "vertique.codegen.restclient.warnExternalBeans"   // default: true
})
```

> **Note:** `vertique.codegen.package` is **not supported for REST client artifacts**. The processor
> ignores it for placement: the `{Client}_RestClientProxy` and every `{Bean}_BeanParamAccessor` are
> always generated into the package of their origin type, because the runtime locates each companion
> by that name and package. Setting the option does not move the generated classes, so it has no
> supported use here. Leave the option unset for projects using `@RestClient`.

### Validators

Each validator emits compile-time diagnostics via a shared `Diagnostics` helper (from `vertique-codegen-core`):

| Validator | What it checks | Diagnostic |
|-----------|---------------|------------|
| `ReturnTypeValidator` | Method must return `Future<T>` | `ERROR` |
| `PathPlaceholderValidator` | `{name}` in `@Path` must have a matching `@PathParam("name")` and vice versa | `ERROR` |
| `HttpVerbValidator` | Non-`default` methods, including the abstract ones inherited from super-interfaces, must have one of `@GET`/`@POST`/`@PUT`/`@DELETE`/`@PATCH`/`@HEAD`, on the method or on a declaration it overrides; `@OPTIONS` intentionally rejected to match the runtime scanner's supported set | `ERROR` |

### Inherited operations

The processor sees the operations the runtime scanner sees: the abstract methods the interface declares and
the ones it inherits from its super-interfaces. A declaration that another method overrides is folded into the
overrider, so `delete(String)` of a `Crud<String>` client and `Crud<ID>.delete(ID)` are one operation that reads
the annotations of both, nearest first. Parameter and return types are the ones the interface binds
(`delete(String)`, not `delete(ID)`). A `default` method is not an operation but overrides, and is overridden,
like any other: a default `close()` replaces an inherited abstract `close()`, and an abstract redeclaration of a
default method with a verb keeps that verb. The proxy keys each operation by the interface that declares the
method it implements, which is the key of the runtime scan.

When an operation is declared by an interface the generated proxy cannot name (package-private to another
package, or nested in a private type), compilation fails with an error that names the declaring interface, the
client and the package the proxy is generated in. Make the interface (and any enclosing type) public, or move
the client into its package. No proxy is generated and the client does not silently fall back to the runtime
reflective proxy.

### Generated `{Client}_RestClientProxy`

Generates `{ClientSimpleName}_RestClientProxy` in the origin interface's package. Key properties of the generated class:

- Static `Method` constants resolved once in the class initializer via `{Interface}.class.getDeclaredMethod(...)`. Signature-precise and overload-safe — matches the `Map<Method, ClientMethodMeta>` shape the runtime scanner already produces.
- Constructor: `(RestClientDispatcher dispatcher, BeanParamAccessorRegistry registry, Map<Method, ClientMethodMeta> methodMetas)`. Per-method `ClientMethodMeta` references are stored in `final` fields for O(1) access on the hot path.
- Each method body calls `dispatcher.newRequest(meta)`, then for every PATH/QUERY/HEADER/COOKIE parameter calls the matching `dispatcher.applyPathParam`/`applyQueryParam`/`applyHeaderParam`/`applyCookieParam(req, meta, name, value, defaultValue)` — passing the raw typed value, not a `.toString()`-serialized one — and finally calls `dispatcher.send(req, meta)`, all inside one `try` block whose `catch (Exception)` returns `Future.failedFuture(new RestClientException("Failed to build request for <method>", cause))` — the same delivery as the JDK proxy, so a construction failure never throws from the call site. Outbound serialization runs through the dispatcher's param-conversion resolver, so a `UUID`, `java.time` type, enum, or app-registered converter serializes consistently with the inbound jaxrs side. `@BeanParam` fields route through the same four `apply*Param` calls, keyed by each field's JAX-RS wire name.
- A `null` required `@PathParam` (top-level or a `@BeanParam` field) with no `@DefaultValue` fails the returned future with `RestClientException` *before* the dispatcher call — the reflective fallback (`RestClientRequestFactory.collectParamsWithMeta`) fails identically, so both proxy flavors reject the call at the same point.

Example for a two-parameter method:

```java
@Generated("dev.vertique.codegen.rest.client.processor.RestClientProcessor")
public final class UserClient_RestClientProxy implements UserClient {

    private static final Method M_GET_USER;
    static {
        try {
            M_GET_USER = UserClient.class.getDeclaredMethod("getUser", String.class, PageRequest.class);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final RestClientDispatcher dispatcher;
    private final BeanParamAccessor<PageRequest> pageRequestAccessor;
    private final ClientMethodMeta metaGetUser;

    public UserClient_RestClientProxy(
            RestClientDispatcher dispatcher,
            BeanParamAccessorRegistry registry,
            Map<Method, ClientMethodMeta> methodMetas) {
        this.dispatcher = dispatcher;
        this.pageRequestAccessor = registry.resolve(PageRequest.class);
        this.metaGetUser = methodMetas.get(M_GET_USER);
    }

    @Override
    public Future<User> getUser(String id, PageRequest paging) {
        try {
            RestRequestBuilder req = dispatcher.newRequest(metaGetUser);
            if (id == null) {
                throw new RestClientException("path param 'id' was null and has no @DefaultValue");
            }
            req = dispatcher.applyPathParam(req, metaGetUser, "id", id, null);
            req = dispatcher.applyQueryParam(req, metaGetUser, "page", pageRequestAccessor.extract(paging, "page"), null);
            req = dispatcher.applyQueryParam(req, metaGetUser, "size", pageRequestAccessor.extract(paging, "size"), null);
            req = dispatcher.applyHeaderParam(req, metaGetUser, "X-Sort", pageRequestAccessor.extract(paging, "sort"), null);
            return dispatcher.send(req, metaGetUser);
        } catch (Exception _e_) {
            return Future.failedFuture(new RestClientException("Failed to build request for getUser", _e_));
        }
    }
}
```

### Generated `{Bean}_BeanParamAccessor`

Generates `{BeanSimpleName}_BeanParamAccessor` in the bean type's package. Uses a `switch` expression over field names for both record and class bean types, so the JIT can apply tableswitch or indy-string dispatch. The generated class has a public no-arg constructor, which is required by `BeanParamAccessorRegistry.resolve()`.

Field deduplication is subclass-wins: when a subclass field shadows a superclass field of the same name, only the subclass field is emitted, keyed by field name. This prevents duplicate `case` labels in the generated `switch` expression (which would fail to compile in the consumer's build) and matches Java field-hiding semantics.

Example for a record bean:

```java
@Generated("dev.vertique.codegen.rest.client.processor.RestClientProcessor")
public final class PageRequest_BeanParamAccessor implements BeanParamAccessor<PageRequest> {

    public PageRequest_BeanParamAccessor() {}

    @Override
    public Object extract(PageRequest bean, String fieldName) {
        return switch (fieldName) {
            case "page" -> bean.page();
            case "size" -> bean.size();
            case "sort" -> bean.sort();
            default -> throw new IllegalArgumentException("Unknown field: " + fieldName);
        };
    }

    @Override
    public List<String> fieldNames() {
        return List.of("page", "size", "sort");
    }
}
```

---

## Generated Output

### Per-field Access Path Resolution

For each field in a `@BeanParam` type, the emitter selects the first applicable path (in order):

1. **Record component** — call `bean.{name}()` (the generated accessor method).
2. **Public or protected getter** — call `bean.get{Name}()` or `bean.is{Name}()`.
3. **Same-package field** — emit direct field access `bean.{name}` (the generated class is in the same package as the bean, so package-private and public fields are accessible without `setAccessible`).
4. **None of the above** — abort emission for the entire bean (see "Bean-level fallback" below).

### Bean-level Fallback

If any field on a bean has no accessible path (step 4 above), the processor does NOT emit an accessor for that bean. Instead, a `NOTE`-level diagnostic is emitted:

```
{BeanType} has fields not accessible without setAccessible; runtime reflective fallback applies
```

At runtime, `BeanParamAccessorRegistry.resolve(beanType)` gets a `ClassNotFoundException` for the missing `_BeanParamAccessor` class and returns the `ReflectiveBeanParamAccessor` fallback, which handles the entire bean as today.

**There are no partial accessors.** Partial generation would silently drop fields that the runtime currently reads via `setAccessible`-driven private-field reflection.

### Deduplication

When multiple `@RestClient` interfaces in the same compilation unit reference the same `@BeanParam` type, exactly ONE `{Bean}_BeanParamAccessor` is emitted (keyed by bean type FQN). Both client proxies resolve the same accessor at runtime.

---

## Runtime Integration

### `Class.forName` Discovery Contract

Both discovery lookups follow the same pattern: one reflective call per type at first resolution, then cached forever in a `ConcurrentHashMap`.

**Proxy lookup** (in `RestClientBuilder.build`):
```java
// The companion name is the interface's binary name with '$' replaced by '_', plus the suffix.
String generatedFqn = GeneratedNames.companionFqn(clientInterface, "_RestClientProxy");
try {
    Class<?> generated = Class.forName(generatedFqn, true, clientInterface.getClassLoader());
    Constructor<?> ctor = generated.getDeclaredConstructor(
            RestClientDispatcher.class, BeanParamAccessorRegistry.class, Map.class);
    return clientInterface.cast(ctor.newInstance(dispatcher, registry, methodMetas));
} catch (ClassNotFoundException e) {
    return buildReflectiveProxy(clientInterface);   // existing JDK proxy path
} catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
    throw new RestClientConfigurationException(
            "Generated proxy %s present but failed to instantiate".formatted(generatedFqn), e);
}
```

**Accessor lookup** (in `BeanParamAccessorRegistry.resolve`):
```java
// Backed by ClassValue — resolution runs at most once per (type, classloader) pair.
String generatedFqn = GeneratedNames.companionFqn(beanType, "_BeanParamAccessor");
try {
    Class<?> generatedClass = Class.forName(generatedFqn, true, beanType.getClassLoader());
    return (BeanParamAccessor<?>) generatedClass.getDeclaredConstructor().newInstance();
} catch (ClassNotFoundException e) {
    return fallback;    // ReflectiveBeanParamAccessor handles the whole bean
} catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
    throw new RestClientConfigurationException(
            "Generated bean accessor %s present but failed to instantiate".formatted(generatedFqn), e);
}
```

`RestClientBuilder` runs the proxy lookup through a shared helper with the same semantics as the snippet: an absent class falls back, a present class that cannot be instantiated fails loudly.

### `BeanParamAccessorRegistry` Ownership

The registry is process-wide. `BeanParamAccessorRegistry.shared()` returns a static singleton backed by `ReflectiveBeanParamAccessor` as the fallback. `RestClientBuilder` defaults to this shared instance so standalone construction (`new RestClientBuilder(vertx)`, `RestClientBuilder.create(vertx)`) and Dagger-injected construction (`RestClientFactory`) use the same lookup cache. `RestClientModule` provides a `BeanParamAccessorRegistry` binding that returns `BeanParamAccessorRegistry.shared()`. `RestClientBuilder.beanParamAccessorRegistry(...)` allows override (e.g. in tests).

The registry's internal lookup table is backed by `ClassValue<BeanParamAccessor<?>>` rather than a `static final ConcurrentHashMap`. `ClassValue` is classloader-scoped: each classloader gets its own entry, so the registry does not retain bean types across classloader boundaries in OSGi or multi-classloader containers. The observable behavior is one resolved accessor per type, for the lifetime of the classloader.

### Nested-Class FQN Translation

Java uses `$` as the separator for nested classes in binary names, but `.` in source names. A generated class is a top-level class in the origin type's package, so its name flattens the enclosing types: each enclosing simple name is joined with `_`, and the suffix is appended. When a `@BeanParam` type is a nested class (e.g. `com.example.Outer$Inner`), the generated accessor is `com.example.Outer_Inner_BeanParamAccessor`; a nested `@RestClient` interface `com.example.Outer$Inner` gets `com.example.Outer_Inner_RestClientProxy`. The runtime builds the same name from the `Class` by replacing `$` in `Class.getName()` with `_` before appending the suffix, so `Class.forName` finds the companion for both top-level and nested origins.

### `@Url` Validation at Compile Time

The processor enforces the same `@Url` constraints as the runtime scanner:

- A `@Url` parameter type must be `java.net.URI`.
- `@Url` and `@PathParam`/`@QueryParam`/`@HeaderParam`/`@CookieParam`/`@BeanParam` are mutually exclusive on the same parameter.
- `@DefaultValue` is not allowed on a `@Url` parameter.
- `@Path` on the interface or method is not allowed when `@Url` is present on any parameter of that method.

These are compile errors (`ERROR`-level diagnostics), not warnings.

### `ReflectiveBeanParamAccessor` Internal Cache

`ReflectiveBeanParamAccessor` maintains a two-level cache keyed by `(Class, fieldName)`:

- Level 1: `ClassValue<ConcurrentHashMap<String, Resolved>>` — one map per bean class, scoped to the classloader.
- Level 2: the inner map caches the resolved accessor by field name using a sealed `Resolved` carrier: `RecordHit(Method)` for record components, `ClassHit(Field)` for direct field access, and `Miss` as a negative sentinel.

`setAccessible(true)` is called once at resolve time and never again. The `Miss` sentinel prevents repeated superclass walks on unknown field names, so even error paths are bounded — the reflective fallback pays no per-call hierarchy walk or repeated `setAccessible` cost on its hot path.

### PATH `null` Without `@DefaultValue`: Aligned Fail-Fast

Both the generated proxy and the reflective fallback (`RestClientRequestFactory.collectParams`) raise `RestClientException` when a `@PathParam` — whether a top-level method parameter or a `@BeanParam` field — is `null` and no `@DefaultValue` is set. The message is:

```
path param '<name>' was null and has no @DefaultValue
```

Both paths fail fast: the returned future fails with `RestClientException` ("Failed to build request for <method>") whose cause is the descriptive exception above. Applications that rely on a `null` path segment being silently omitted must add `@DefaultValue` or guard against `null` before calling the method.

---

## Pitfalls

- **External `@BeanParam` types skip proxy emission for the interface.** If any `@BeanParam` type on an interface is not in the current compilation unit, the processor cannot scan its fields, so the entire `{Client}_RestClientProxy` is not emitted. A compile-time `WARNING` is emitted for the external type. The interface falls back to the JDK proxy at runtime, exactly as without the processor.

- **Lombok remains explicit.** The public application parent does not activate Lombok. A project
  that uses it appends the Lombok processor path as the explicit opt-in documented in
  `docs/packaging.md`.

- **Bean-level fallback for un-accessible fields.** A bean with private fields and no getters (and no record components) will not receive a generated accessor. The processor emits a `NOTE` rather than a `WARNING` because the runtime handles such beans correctly via `ReflectiveBeanParamAccessor`. If maximum performance is required, add public getters or convert to a record.

- **PATH `null` without `@DefaultValue` fails fast on both paths.** Both the generated proxy and the reflective fallback fail the returned future with `RestClientException` when a `@PathParam` is `null` with no `@DefaultValue`. See "Runtime Integration" above.

---

## Module Dagger Bindings

None. The processor emits no Dagger binding modules. Generated proxies and accessors are discovered at runtime via `Class.forName` and do not require any Dagger graph participation.

---

## Dependencies

- `dev.vertique:vertique-codegen-core` — `CodegenContext`, `TypeResolver`, `AnnotationMirrors`, `Diagnostics`, `Identifiers`
- `com.palantir.javapoet:javapoet` — source generation (compile scope of the processor; not on the application runtime classpath)
- `javax.annotation.processing` APIs — part of the JDK; not a separate Maven dependency
