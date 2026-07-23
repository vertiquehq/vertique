<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Config Module

> **Status:** Stable
> **Package:** `dev.vertique.config`
> **Artifact:** `vertique-config-core` (under the `vertique-config` aggregator)
> **Depends on:** core

Provides multi-source configuration loading using the Vert.x `ConfigRetriever` ecosystem with Dagger integration. In launcher mode, a temporary `Vertx` instance runs the full retriever chain before the application `Vertx` is created, producing a resolved `JsonObject` that becomes the canonical configuration tree. In legacy (non-launcher) mode, `ConfigBootstrap` bridges the async retriever load before a Dagger component is built.

---

## Bootstrap Loading (Launcher Mode)

Applications launched via `VertiqueApplication` receive fully resolved configuration through `BootstrapConfigLoader`. The loader runs a two-phase synchronous load on a temporary, minimal `Vertx` instance (one event-loop thread, one worker) before any contributor or application `Vertx` is created. The temporary instance and its `ConfigRetriever` are fully shut down before the call returns.

**Threading constraint:** `BootstrapConfigLoader.load` must be called from a non-Vert.x thread. It throws `IllegalStateException` immediately if a Vert.x context is active on the calling thread.

### Phase 1

The default store chain is run and the `--conf` overlay is applied at highest precedence.

### Phase 2 (conditional)

If the phase-1 merged tree contains a `config.stores` array, each declared store is validated and the chain is rebuilt with those stores inserted in the declared-store slot (see precedence table below). The `--conf` overlay is re-applied. Phase 2 is skipped entirely when `config.stores` is absent or empty.

### Precedence Chain (lowest → highest)

| Priority | Source | Notes |
|----------|--------|-------|
| 1 (lowest) | `*.json` from each config dir | Optional — scanned from `config/` or `VERTX_CONFIG_LOCATIONS` dirs |
| 2 | `*.properties` from each config dir | Hierarchical key expansion (`http.port=8080` → nested JSON); optional |
| 3 | Declared `config.stores` entries | Inserted here — list order, later entries override earlier ones |
| 4 | Environment variables | `ENV_VAR` style |
| 5 | System properties | `-Dkey=value` style |
| 6 (highest) | `--conf` overlay | CLI `--conf` or `DeploymentOptions.setConfig()`; always wins |

Config directories default to `config/`. Override with the `VERTX_CONFIG_LOCATIONS` environment variable (comma-separated list of directories). Later directories override earlier ones. Within each directory, `*.properties` overrides `*.json`.

The resolved tree is installed as the main verticle's deployment config by `VertiqueApplication.beforeDeployingVerticle`, so `MainVerticle.config()` always returns the canonical merged tree.

### Failure Handling

Bootstrap failures map to exit code `ExitCodes.VERTX_INITIALIZATION` (11) via `VertiqueApplication`. The exception thrown depends on the failure:

- `BootstrapConfigException` — timeout, I/O error, unknown store/source type, invalid declaration, or factory instantiation failure.
- `PlaceholderResolutionException` — unresolvable placeholder reference (pass 1 "tree references only", or pass 3 full-tree resolution).
- `ConfigPropertySourceException` — unrecoverable source lookup error during pass 3.
- `IllegalStateException` — `load()` called from a Vert.x event-loop or worker thread.

---

## Pluggable Stores (`config.stores`)

Applications can declare additional Vert.x `ConfigStore` instances in the merged config tree. The bootstrap loader reads these declarations from phase 1 and uses them in phase 2.

### Declaration Shape

Each entry in the `config.stores` array is a JSON object:

```json
{
  "config": {
    "stores": [
      {
        "type": "configmap",
        "config": {
          "namespace": "my-app",
          "name":      "app-config"
        }
      },
      {
        "type":   "aws-ssm",
        "format": "properties",
        "config": {
          "path": "/my-app/prod/"
        }
      }
    ]
  }
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `type` | Yes | Non-blank string; must match a registered `ConfigStoreFactory` name |
| `config` | No | `JsonObject` passed as-is to the store factory; defaults to `{}` |
| `format` | No | Override the store's default format (e.g. `"properties"`, `"yaml"`) |

The `type` field is matched against all `ConfigStoreFactory` implementations registered via `META-INF/services/io.vertx.config.spi.ConfigStoreFactory` on the classpath.

### Precedence Within the Declared-Store Slot

Declared stores occupy a single priority slot (above file directories, below env/sys). Within that slot, list order determines override behavior: a later store in the array wins on key collision (Vert.x merge semantics — last writer wins).

This is whole-subtree **merge** with later-overrides. This is distinct from the `config.propertySources` mechanism, which uses first-hit-wins key **lookup** for `${...}` placeholders.

### Extension Store Dependencies

Extension store types require their module on the classpath:

| Type | Required artifact |
|------|-------------------|
| `"configmap"` | `io.vertx:vertx-config-kubernetes-configmap` |
| `"aws-ssm"` | `dev.vertique:vertique-config-aws-ssm` |

If a declared type is unknown at startup, `BootstrapConfigLoader` throws `BootstrapConfigException` with a dependency hint naming the required artifact (for the types listed above).

### Non-Optional Semantics

Declared stores are non-optional. A store that cannot be reached or fails to load aborts startup. This is intentional: a declared store that is unreachable is a misconfiguration that must be surfaced immediately rather than silently degrading.

### Volume-Mounted Files vs API Stores

For Kubernetes ConfigMaps mounted as files, use `VERTX_CONFIG_LOCATIONS` to add the mount path to the directory scan — no `config.stores` declaration needed. The `configmap` API store type is for ConfigMaps accessed via the Kubernetes API, typically when the application lacks filesystem access to the mount or needs dynamic updates without a pod restart.

---

## Placeholders

After the final merged tree is assembled (post-stores phase), the bootstrap engine resolves
`${...}` placeholder references in every string value. Resolution runs once, eagerly, before
`Vertx` is created.

### Grammar

| Syntax | Meaning |
|--------|---------|
| `${key}` | Resolve `key` through the progressive chain. No default — fail-closed if unresolved. |
| `${key:default}` | Bare-colon default: the **first top-level colon** (at brace depth 1, not inside a nested `${}`) splits key from default. The entire suffix is the default text, so `${endpoint:https://collector:4317}` has key `endpoint` and default `https://collector:4317`. `${key:}` yields an empty-string default. |
| `\${` | Escape: emits the literal text `${` and advances the scanner by 3. A `\` not followed by `${` is a literal backslash. |

Multiple placeholders in one value concatenate as strings: `jdbc:postgresql://${db.host}:${db.port}/app`.

### Resolution Chain

For each key the engine consults:

1. **Tree probe** — flat-key probe first (finds `DB_PASSWORD` stored flat by the env store), then dot-path walk (descends nested `JsonObject`s for keys like `db.host`). JSON `null` is treated as not-found. The tree already reflects the full env/sys/`--conf` precedence hierarchy.
2. **Declared property sources** in list order, first non-empty hit wins. Source values are **literal** — a `${...}` inside a secret value passes through unchanged.
3. **Default** — if all chain steps missed and a default was declared, the default text is itself resolved (may contain placeholders, subject to `MAX_DEPTH = 5`).
4. **Failure** — the reference is recorded and the walk continues; all failures are reported together.

The chain order is tree-first. This preserves the existing env/sys override story (a `-Dkey=value` wins without touching the vault) and enables the self-reference idiom below.

### Self-Reference Idiom

```json
{
  "db": {
    "password": "${db.password}"
  }
}
```

When the engine begins resolving `db.password` and encounters another reference to `db.password`
on the resolution stack, it treats the recursive tree probe as not-found and falls through to the
declared property sources. This is the recommended pattern for pulling a secret from a vault
without writing the value in any config file. The reference fails only when the entire chain —
tree (skipped due to self-reference) + all declared sources + default — is exhausted.

A genuine cycle (`a: "${b}"`, `b: "${a}"`) surfaces as two unresolved references with chain
renderings (e.g. `"a -> b -> a"`) rather than a clean error message. `MAX_DEPTH = 5` is the
backstop against unbounded recursion.

### Failure Behavior

A reference with no default that exhausts the chain aborts startup. The engine collects all
failures across the entire tree walk and throws a single `PlaceholderResolutionException` with a
sorted, de-duplicated list of unresolved reference renderings (key names and chain strings). The
list contains only key names — never resolved values.

A source **error** (auth failure, network error, malformed response) aborts startup immediately.
Source errors are never degraded into not-found and are never masked by a default.

`VertiqueApplication` maps any `PlaceholderResolutionException` to exit code
`ExitCodes.VERTX_INITIALIZATION` (11).

### Type Preservation

When a config value is exactly one placeholder token with no surrounding text, the resolved JSON
type is preserved: a tree `Integer` stays `Integer`, a tree `JsonObject` stays `JsonObject`.
Property-source values are always strings (V1).

### NFR-CONF-002 — Value Redaction

Resolved values **must not** appear in logs at any level, in exception messages, or in `toString()`
of engine internals. The resolution engine logs placeholder keys and key counts only. Implementations
of `ConfigPropertySource` and `ConfigPropertySourceFactory` must follow the same rule. A
log-capture test asserts no sentinel secret value appears in any log line for success and failure
paths.

### Three-Pass Model

Resolution runs in three ordered passes:

1. **Pass 1 — `config.propertySources` subtree (tree-only):** source declarations are resolved
   against the merged tree. Source configs may reference env vars and tree keys but must not
   reference values that require a property source. An unresolvable reference here fails with
   "bootstrap subtrees may use tree references only".
2. **Pass 2 — source instantiation:** each resolved `config.propertySources` entry is validated
   and a `ConfigPropertySource` is created in declared order. Factory failures close
   already-created sources (reverse order) and abort startup.
3. **Pass 3 — whole-tree resolution:** every string value in the merged tree is resolved against
   the full chain (tree + sources). Pass 3 always runs even when no sources are declared.

---

## Property-Source SPI (`config.propertySources`)

Declare property sources under `config.propertySources` to pull secrets from external backends
into placeholder references. Declarations are a JSON array; list order is lookup precedence among
sources.

### Declaration Shape

```json
{
  "config": {
    "propertySources": [
      { "name": "kv-app",    "type": "azure-keyvault", "endpoint": "https://app.vault.azure.net" },
      { "name": "kv-shared", "type": "azure-keyvault", "endpoint": "https://shared.vault.azure.net" },
      { "name": "db-secret", "type": "aws-secrets", "region": "eu-west-1",
        "secrets": [ { "secretId": "prod/db", "prefix": "db." } ] }
    ]
  }
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `type` | Yes | Non-blank string; must match a registered `ConfigPropertySourceFactory` type |
| `name` | No | Instance name for diagnostics; defaults to `type[index]` (e.g. `azure-keyvault[0]`) |
| *(other fields)* | — | Factory-specific config; passed as-is to `ConfigPropertySourceFactory.create` |

Unknown `type` values abort startup with a message naming the required module dependency.

The `config.propertySources` subtree may itself contain `${key}` references to tree keys (env
vars, system properties, other tree values), but must not reference values that require a property
source (no source-on-source recursion — Pass 1 is tree-only).

### Key Interfaces

**`ConfigPropertySource`** — resolves a single key to a string value.

```java
public interface ConfigPropertySource extends AutoCloseable {
    String name();
    Optional<String> lookup(String key);  // throws ConfigPropertySourceException (unchecked) on error
    default void close() {}
}
```

**`ConfigPropertySourceFactory`** — creates `ConfigPropertySource` instances. Discovered via `ServiceLoader`; register in `META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory`.

```java
public interface ConfigPropertySourceFactory {
    String type();
    ConfigPropertySource create(String name, JsonObject sourceConfig);
}
```

### Not-Found vs Error Contract

- Return `Optional.empty()` — key not found in this source; resolution chain continues to the next source.
- Throw `ConfigPropertySourceException` — unrecoverable error (auth failure, network error, malformed response); startup aborts immediately. Exception messages MUST contain the source name and key but MUST NOT contain any resolved value.

### Source Lifecycle

Sources are created at bootstrap in declared order, closed at shutdown in reverse declaration
order. `close()` MUST NOT throw — any exception is a programming error.

During bootstrap, results are memoized per `(source, key)` — `lookup` is called at most once per
key per source. Sources are consulted only during single-threaded bootstrap; no thread-safety
contract is required of implementations.

### Extension Source Dependencies

Extension source types require their module on the classpath:

| Type | Required artifact |
|------|-------------------|
| `"vault"` | `dev.vertique:vertique-config-vault` |
| `"aws-secrets"` | `dev.vertique:vertique-config-aws-secrets` |
| `"azure-keyvault"` | `dev.vertique:vertique-config-azure-keyvault` |

---

## Config-Parser Seam (`dev.vertique.core.config`, `dev.vertique.config.parser`)

Config parsing is exposed as the injected `ConfigParser` **interface** in `vertique-core`; the implementation and
mapper assembly live in `vertique-config-core`. The seam is wired once per application by `ConfigParsingModule`.

### ConfigParser (interface — `vertique-core` · `core.config`)

The canonical injectable facade for parsing `JsonObject` configuration sections into typed records. Every module boundary provider injects a `ConfigParser` and parses its config section through it.

```java
public interface ConfigParser {

    /** Parses a section into a typed record (null/empty → type default shape). */
    <T> T parse(JsonObject section, Class<T> type);

    /** Parses a section that IS a keyed object {key:{...}} into a list, injecting
     *  each entry's key into identityProp of every element. */
    <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType);

    /** As above, additionally injecting every fixedProps entry into each element before
     *  deserialization (so a validating compact constructor sees all required fields). */
    <T> List<T> parseKeyedObject(
            JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps);
}
```

The underlying mapper is **isolated** from Vert.x's `DatabindCodec.mapper()` and any REST/JSON mapper. It registers
`Jdk8Module`, `JavaTimeModule`, `KeyedCollectionModule` (from `vertique-json`), and `VertxModule`; it applies lenient
scalar coercion and tolerates unknown properties, independent of any strictness the REST-input mapper is given.

**Invariants and Gotchas:** parse errors that originate from a config record's own compact-constructor validator
(`ConfigurationException`) are propagated as-is with their value-free message. Raw Jackson type-mismatch errors are
wrapped in a new `ConfigurationException` with a value-free generic message (SEC-1 secret non-leakage). Never catch
`JsonProcessingException` from `ConfigParser` directly — it always wraps into `ConfigurationException` before surfacing.

**Typical usage:**

```java
@Provides @Singleton
static RestConfig restConfig(@VertxConfig JsonObject config, ConfigParser parser) {
    return parser.parse(JsonConfigPaths.navigateObject(config, "rest"), RestConfig.class);
}
```

### @ConfigMapper (qualifier — `vertique-core` · `core.config`)

`@Qualifier` annotation marking an `ObjectMapper` an application supplies to customize config parsing. When bound, the
framework re-layers its mandatory modules and lenient policy over the supplied mapper (in place — no copy) and uses the
result as the config mapper backing the injected `ConfigParser`. The mapper is dedicated to and owned by config parsing;
the framework finalizes it before first use.

```java
// Application override — adds a module; framework layers the mandatory bits on top:
@Provides @ConfigMapper
static ObjectMapper appConfigMapper() {
    return JsonMapper.builder().addModule(new MyConfigModule()).build();
}
```

The override is optional: absent any `@ConfigMapper` binding, the framework uses `DefaultConfigMapper.lenient()`.

### ConfigParsingModule (Dagger module — `vertique-config-core` · `config.parser`)

Abstract Dagger `@Module` providing the single `ConfigParser` binding. **Must be listed in every application
`@Component`** alongside `VertxModule`. Omitting it yields a Dagger missing-binding compile error.

```java
@Module
public abstract class ConfigParsingModule {

    @BindsOptionalOf @ConfigMapper
    abstract ObjectMapper configMapperOverride();

    @Provides @Singleton
    static ConfigParser configParser(@ConfigMapper Optional<ObjectMapper> override) {
        ObjectMapper mapper = override
                .map(DefaultConfigMapper::finalizeForConfig)
                .orElseGet(DefaultConfigMapper::lenient);
        return new DefaultConfigParser(mapper);
    }
}
```

There is exactly one `ConfigParser` binding in any correctly wired graph. Feature modules that inject `ConfigParser`
depend only on `vertique-core`; Dagger resolves the binding from `ConfigParsingModule` at the application layer.

**There is no transitive auto-include.** The only universally-included framework modules (`VertxModule`,
`CoreLifecycleStepsModule`) live in `vertique-core`, which cannot depend on `vertique-config-core`.

### DefaultConfigMapper (Internal — `vertique-config-core` · `config.parser`)

Factory for the config `ObjectMapper`. Not part of the stable API — call sites should inject `ConfigParser` and use the
`@ConfigMapper` seam rather than constructing mappers directly.

| Method | Purpose |
|--------|---------|
| `lenient()` | Builds the default isolated config mapper: lenient coercion, `FAIL_ON_UNKNOWN_PROPERTIES` disabled, four mandatory modules registered. Each call returns a fresh instance. |
| `finalizeForConfig(ObjectMapper)` | Re-layers the framework's mandatory modules and lenient policy over an application-supplied override **in place** (no copy — `.copy()` throws on `JsonMapper` subclasses). Returns the same instance. |

`finalizeForConfig` applies: `registerModules(Jdk8Module, JavaTimeModule, KeyedCollectionModule, VertxModule)` (duplicate-safe via `getTypeId()`), `FAIL_ON_UNKNOWN_PROPERTIES = false`, and lenient scalar coercion via `coercionConfigDefaults()`.

### DefaultConfigParser (Internal — `vertique-config-core` · `config.parser`)

The `ConfigParser` implementation. Constructed by `ConfigParsingModule` over the mapper chosen at provision time.
Also directly constructible in tests via the public `DefaultConfigParser(ObjectMapper)` constructor.

### ConfigTreeBuilder (`vertique-core` · `core.config`)

Converts the flat key/value pairs that Spring/Quarkus host bridges expose (`Environment` property names, SmallRye/MicroProfile config keys) into the nested `JsonObject` that `ConfigParser` and `JsonConfigPaths` consume. It is the inverse of `JsonConfigPaths.navigateObject`. Spring and Quarkus bridges are its primary consumers; the resulting `JsonObject` is then passed into `VertiqueRuntime.of(vertx, tree)`.

```java
public static JsonObject build(Map<String, String> flatKeys)
```

The builder is **purely syntactic** — it decides only the shape (object / array / literal map key), never the value type. All leaf values are stored as `String`; type coercion is `ConfigParser`'s job downstream.

#### Key Grammar (frozen)

| Form | Interpretation |
|------|---------------|
| Bare segment (incl. all-digit, e.g. `2026`) | Object key |
| `[N]` — non-negative integer | Array index; must be contiguous from `0` |
| `[content]` — quoted or dot-containing | Literal map key (dots preserved, surrounding quotes stripped) |

**Examples:**

```
a.b=1, a.c=2                            → {"a":{"b":"1","c":"2"}}
years.2026.total=5                      → {"years":{"2026":{"total":"5"}}}
servers[0].host=h, servers[1].host=k   → {"servers":[{"host":"h"},{"host":"k"}]}
audit.bindings[http.server].dim[0]=x   → {"audit":{"bindings":{"http.server":{"dim":["x"]}}}}
tags[0]=a, tags[1]=b                   → {"tags":["a","b"]}
```

**Fail-fast rules:** non-contiguous array indices, duplicate indices, mixing `[N]` with object keys at the same node, or a path used as both a leaf and a parent all throw `ConfigurationException`. Error messages name offending **keys/paths only, never values** (secret non-leakage). Input order is irrelevant — keys are processed in sorted order.

---

## Key Classes

### BootstrapConfigLoader

Performs the pre-Vertx two-phase synchronous bootstrap load. Called by `VertiqueApplication.createVertxBuilder`; not normally called directly by application code.

```java
public final class BootstrapConfigLoader {

    public static final String CONFIG_SECTION        = "config";
    public static final String STORES_KEY            = "stores";
    public static final String PROPERTY_SOURCES_KEY  = "propertySources";
    public static final long   BOOTSTRAP_TIMEOUT_MS  = 30_000;

    /**
     * Performs the two-phase bootstrap load on a temporary Vertx.
     * Must be called from a non-Vertx thread.
     */
    public static BootstrapResult load(JsonObject deploymentConfig) { ... }

    public record BootstrapResult(JsonObject config, List<ConfigPropertySource> propertySources) {}
}
```

**Invariants and Gotchas:**
- `load` is synchronous and blocks the calling thread for the duration of the retriever cycle. Call it only from the main thread or a framework-owned startup thread, never from a Vert.x event-loop or worker.
- The timeout applies to each individual retrieval step (phase 1, phase 2) and to the temp-Vertx close — each step gets its own 30-second window.
- If the close step fails after a successful load, the failure is logged as a warning but does not propagate (the valid result is returned). If the close step fails after a failed load, the close failure is suppressed and the load exception propagates.

### ConfigBootstrap

Static helper providing the default `ConfigRetrieverOptions` chain. The `load` overloads are deprecated in launcher mode.

```java
public final class ConfigBootstrap {

    public static final String CONFIG_LOCATIONS_ENV = "VERTX_CONFIG_LOCATIONS";
    public static final String DEFAULT_CONFIG_DIR   = "config";

    /** Returns the default chain (file dirs → env → sys). Not deprecated. */
    public static ConfigRetrieverOptions defaultOptions() { ... }

    /** Returns the default chain with declared stores inserted before env/sys. Not deprecated. */
    public static ConfigRetrieverOptions defaultOptions(List<ConfigStoreOptions> declaredStores) { ... }

    /** @deprecated Use VertiqueApplication; the resolved tree is passed as deployment config automatically. */
    @Deprecated
    public static Future<Result> load(Vertx vertx, JsonObject deploymentConfig) { ... }

    /** @deprecated Use VertiqueApplication or BootstrapConfigLoader for manual bootstrap. */
    @Deprecated
    public static Future<Result> load(Vertx vertx, JsonObject deploymentConfig,
                                      ConfigRetrieverOptions options) { ... }

    public record Result(JsonObject config, ConfigRetriever retriever) {}
}
```

### ConfigModule

Concrete Dagger `@Module` that provides `ConfigRetriever` as an injectable singleton. Deprecated for launcher-mode applications; omit entirely when using `VertiqueApplication`.

```java
@Module
public class ConfigModule {

    /** @deprecated Omit when using VertiqueApplication. */
    @Deprecated
    public ConfigModule(ConfigRetriever retriever) { ... }

    @Provides @Singleton
    ConfigRetriever configRetriever() { ... }
}
```

**Bindings provided:**

| Type | Qualifier | Description |
|------|-----------|-------------|
| `ConfigRetriever` | — | The Vert.x config retriever (legacy path only) |

---

## Legacy Path (Non-Launcher Mode)

For applications that use `MainVerticle` without `VertiqueApplication`, `ConfigBootstrap.load` continues to work exactly as before.

```java
// Legacy wiring inside MainVerticle.start() — still functional, deprecated
@Override
public void start(Promise<Void> startPromise) {
    ConfigBootstrap.load(vertx, config())
        .compose(result -> {
            AppComponent app = DaggerAppComponent.builder()
                .vertxModule(new VertxModule(vertx, result.config()))
                .configModule(new ConfigModule(result.retriever()))
                .build();
            return vertx.deployVerticle(app.httpVerticle());
        })
        .onSuccess(id -> startPromise.complete())
        .onFailure(startPromise::fail);
}
```

**Legacy under launcher compatibility note:** if a legacy `MainVerticle` (using `ConfigBootstrap.load` internally) is deployed under `VertiqueApplication`, the bootstrap will run twice. The second load (inside the verticle) merges the already-resolved tree against itself — a benign no-op with no correctness impact. No special detection or marker key is needed.

**Migration path:** replace `ConfigBootstrap.load` + `ConfigModule` with direct Dagger component construction from `config()`. The resolved tree is already in `config()` when running under `VertiqueApplication`.

```java
// Launcher-mode wiring inside MainVerticle.start() — no ConfigBootstrap needed
@Override
public void start(Promise<Void> startPromise) {
    AppComponent app = DaggerAppComponent.builder()
        .vertxModule(new VertxModule(vertx, config()))
        .build();
    vertx.deployVerticle(app.httpVerticle())
        .onSuccess(id -> startPromise.complete())
        .onFailure(startPromise::fail);
}
```

---

## Config-Backed Authorization (`dev.vertique.security.config`)

The `dev.vertique.security.config` package is the config-aware home for authorization policy and role wiring. The security-core module ships only the authorization SPIs and in-memory defaults; by keeping the YAML/config-backed sources in `vertique-security-config`, the security-core stays free of any config-module dependency.

### AuthzConfigModule

Abstract Dagger `@Module` that reads the `authorization` section of the application config and contributes config-backed implementations of the core authz SPIs into the multibinding sets declared by `SecurityAuthzModule`. An application using config-backed authorization installs **both** `AuthzConfigModule` and `SecurityAuthzModule` — the core module declares the sets and builds the engine; `AuthzConfigModule` feeds config-derived entries into them.

**Bindings provided (all `@IntoSet`):**

| Type | Multibinding set | Notes |
|------|-----------------|-------|
| `PolicyDefinitionSource` | `Set<PolicyDefinitionSource>` | `ConfigBackedPolicyDefinitionSource`, validated at startup |
| `RolePolicyResolver` | `Set<RolePolicyResolver>` | `ConfigBackedRolePolicyResolver`, policy-catalogue validation at wiring time |

### ConfigBackedPolicyDefinitionSource

Implements the core `PolicyDefinitionSource` SPI. Reads `authorization.policies` at construction time and converts each `PolicyDefinitionConfig` to a `PolicyDefinition`. Action patterns are validated against the `ActionRegistry` by `PolicyDefinitionSource.validateAgainst`, which the core wiring layer calls polymorphically at startup (fail-fast).

### ConfigBackedRolePolicyResolver

Implements the core `RolePolicyResolver` SPI. Reads `authorization.rolePolicies` at construction time. Policy-name existence is **not** validated in this class — it is validated by `AuthzConfigModule` against the merged `Set<PolicyDefinitionSource>` at wiring time, so a mapping that references a policy contributed programmatically through another source is accepted while an unknown policy still fails fast with an `IllegalStateException`.

### AuthorizationConfig

Config record deserialized from the `authorization` section via `ConfigParser`. Holds two sections:

- `rolePolicies` — user-defined dictionary mapping role names to lists of policy names.
- `policies` — list of `PolicyDefinitionConfig` entries defining each named policy.

Defaults to no role mappings and no inline policies when the `authorization` section is absent.

### PolicyDefinitionConfig

Config record for a single named policy (`authorization.policies[]`). Holds a non-blank `name` and a list of `PolicyStatementConfig` entries.

### PolicyStatementConfig

Config record for a single policy statement (`authorization.policies[].statements[]`). Holds an `Effect` (defaults to `ALLOW`) and a list of action pattern strings (exact or wildcard, e.g. `cms.content.*`).

### Configuration example

```yaml
authorization:
  rolePolicies:
    admin:
      - admin-policy
    viewer:
      - viewer-policy
  policies:
    - name: admin-policy
      statements:
        - effect: ALLOW
          actions:
            - cms.content.*
    - name: viewer-policy
      statements:
        - effect: ALLOW
          actions:
            - cms.content.read
```

---

## Extension Points

### `ConfigBootstrap.defaultOptions(List<ConfigStoreOptions>)`

Applications that need a custom retriever (outside the standard bootstrap) can build on the default chain with additional stores:

```java
ConfigRetrieverOptions opts = ConfigBootstrap.defaultOptions(List.of(
    new ConfigStoreOptions()
        .setType("http")
        .setConfig(new JsonObject().put("host", "config-server").put("port", 8888))
));
// Use opts with a ConfigRetriever created on a live Vertx instance
```

### `ConfigPropertySourceFactory` SPI

Provide a `ConfigPropertySource` implementation for a custom secret/property backend:

1. Implement `ConfigPropertySourceFactory` (returns the type key and creates `ConfigPropertySource` instances).
2. Register in `META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory`.
3. Declare instances under `config.propertySources` in application config.

---

## Dependencies

- `dev.vertique:vertique-core` — `ConfigParser` interface, `@ConfigMapper` qualifier, `@KeyedBy` annotation
- `dev.vertique:vertique-json` — `KeyedCollectionModule` and keyed-collection deserialization support used by `DefaultConfigMapper`
- `io.vertx:vertx-config` — `ConfigRetriever`, store types, and the `ConfigStoreFactory` SPI
- `com.fasterxml.jackson.databind:jackson-databind` — `ObjectMapper` used by `DefaultConfigMapper` and `DefaultConfigParser`
- `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` — mandatory config module (Jdk8Module)
- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` — mandatory config module (JavaTimeModule)
- `com.google.dagger:dagger`
- `jakarta.inject:jakarta.inject-api`
- `org.slf4j:slf4j-api`
- `org.projectlombok:lombok` (provided scope)

---

## Related ADRs

- ADR-0095: ServiceLoader as the Pre-DI Bootstrap Seam — establishes `VertxBuilderContributor` discovery and `VertiqueApplication` as the framework application entrypoint; the bootstrap config load (this module) runs inside `createVertxBuilder` to satisfy the contributor config-access requirement.
- ADR-0096: Bootstrap Config Relocation and `config.stores` Two-Phase Load — records the temporary-Vertx approach, the two-phase `config.stores` declaration model, the retriever-fate and deprecation decisions, the `vertx.options` overlay contract, and the distinction between merge-based `config.stores` and lookup-based `config.propertySources`.
- ADR-0097: Placeholder Grammar and Progressive Resolution Chain — records the no-prefix Spring-faithful grammar, tree-first chain order, self-reference fall-through, defaults as fail-closed opt-out, source-values-literal rule, not-found-vs-error contract, three-pass model, type preservation, and store-vs-property-source positioning.
- ADR-0113: Federated Action and Policy Authorship for Framework Authorization — establishes the `PolicyDefinitionSource` and `RolePolicyResolver` SPIs that `ConfigBackedPolicyDefinitionSource` and `ConfigBackedRolePolicyResolver` implement; the config-backed sources in this module are the YAML-backed defaults for that federated contract.
- ADR-0128: Pre-Dagger Config-Mapper Seam — No JVM-Global Override, Not Config-Selected — established the original `TypedConfigParser` / `DefaultConfigMapper` seam and the core constraints: no JVM-global override, not config-selected, isolation from the json-001 `ObjectMapperCustomizer` pipeline. Those constraints remain in force; the implementation was superseded by ADR-0134.
- ADR-0134: Injectable Config Parser — De-static + Three-Way Implementation Split — records the de-static migration (`ConfigParser` interface in `vertique-core`; `DefaultConfigParser` + `DefaultConfigMapper` + `ConfigParsingModule` in `vertique-config-core`; keyed-collection processing in `vertique-json`); the `@ConfigMapper` optional override seam with finalize-in-place semantics; and the mandatory-`ConfigParsingModule`-per-`@Component` requirement.
