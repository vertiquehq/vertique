<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Config Module

> **Status:** Stable
> **Package:** `dev.vertique.config`
> **Artifact:** `vertique-config-core` (under the `vertique-config` aggregator)
> **Depends on:** core, json

Multi-source configuration loading built on the Vert.x `ConfigRetriever` ecosystem, plus the Dagger
seam that parses the resolved tree into typed records. Under `VertiqueApplication`, the whole
retriever chain runs on a temporary `Vertx` instance before the application `Vertx` exists, producing
one resolved `JsonObject` that becomes the canonical configuration tree for the process.

Two mechanisms sit on top of that tree. **Declared stores** (`config.stores`) add further Vert.x
config stores whose contents are *merged* into the tree. **Property sources**
(`config.propertySources`) add external secret backends that `${...}` placeholders *look up* by key.
Both are fail-closed: an unreachable store or an unresolvable reference aborts startup rather than
degrading silently.

---

## When To Use It

Every Vertique application needs this artifact: it provides `ConfigParsingModule`, the Dagger module
that supplies the `ConfigParser` binding every framework module's config provider injects. Omitting it
is a Dagger missing-binding compile error.

Add a provider artifact alongside it only when the application pulls configuration or secrets from an
external backend — see the store and source dependency tables below.

---

## Bootstrap Loading

An application launched via `VertiqueApplication` receives fully resolved configuration through
`BootstrapConfigLoader`. The loader runs a two-phase synchronous load on a temporary, minimal `Vertx`
instance (one event-loop thread, one worker) before any contributor or application `Vertx` is created.
The temporary instance and its `ConfigRetriever` are fully shut down before the call returns.

The resolved tree is installed as the main verticle's deployment config, so `MainVerticle.config()`
always returns the canonical merged tree.

| Phase | What runs |
|---|---|
| Phase 1 | The default store chain, with the `--conf` overlay applied at highest precedence. |
| Phase 2 *(conditional)* | Skipped unless the phase-1 tree declares a non-empty `config.stores`. Each declared store is placeholder-resolved against the phase-1 tree, validated, and inserted into the chain in the declared-store slot; the whole chain is re-run and the `--conf` overlay re-applied. |

### Precedence Chain (lowest → highest)

| Priority | Source | Notes |
|----------|--------|-------|
| 1 (lowest) | `*.json` from a config dir | Optional; scanned from `config/` or the `VERTX_CONFIG_LOCATIONS` dirs |
| 2 | `*.properties` from the same config dir | Hierarchical key expansion (`http.port=8080` → nested JSON); optional |
| 3 | Declared `config.stores` entries | Inserted here — list order, later entries override earlier ones |
| 4 | Environment variables | `ENV_VAR` style |
| 5 | System properties | `-Dkey=value` style |
| 6 (highest) | `--conf` overlay | CLI `--conf` or `DeploymentOptions.setConfig()`; always wins |

Config directories default to `config/`. Override with the `VERTX_CONFIG_LOCATIONS` environment
variable (a comma-separated list of directories). Rows 1 and 2 repeat **per directory** in listed
order, so a later directory's `*.json` overrides an earlier directory's `*.properties`; within one
directory, `*.properties` overrides `*.json`.

### Invariants & Gotchas

- `BootstrapConfigLoader.load` must be called from a **non-Vert.x thread**. It throws
  `IllegalStateException` immediately if a Vert.x context is active on the calling thread.
- `load` is synchronous and blocks the caller for the duration of the retriever cycle. Call it only
  from the main thread or a framework-owned startup thread.
- The 30-second timeout applies to each awaited step independently — phase 1, phase 2, and the
  temporary-`Vertx` close each get their own window.
- A failure to close the temporary `Vertx` is logged at `WARN` and never propagates: it can mask
  neither a successful load nor a load failure.

---

## Declared Stores (`config.stores`)

Declare additional Vert.x `ConfigStore` instances in the merged config tree. The bootstrap loader
reads these from phase 1 and uses them in phase 2.

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

`type` is matched against every `ConfigStoreFactory` registered under
`META-INF/services/io.vertx.config.spi.ConfigStoreFactory` on the classpath. A store declaration may
itself contain `${...}` placeholders, but only **tree** references — it is resolved against the
phase-1 tree before any property source exists.

### Extension Store Dependencies

| Type | Required artifact |
|------|-------------------|
| `"configmap"` | `io.vertx:vertx-config-kubernetes-configmap` |
| `"aws-ssm"` | `dev.vertique:vertique-config-aws-ssm` |

An unknown type at startup raises `BootstrapConfigException` with a dependency hint naming the
required artifact for the types above.

### Invariants & Gotchas

- **Declared stores occupy one precedence slot.** Within that slot, list order decides: a later store
  wins on key collision (Vert.x merge semantics — last writer wins).
- **This is whole-subtree merge**, not key lookup. It is distinct from `config.propertySources`, which
  uses first-hit-wins key lookup for `${...}` placeholders.
- **Declared stores are non-optional.** A store that cannot be reached or fails to load aborts
  startup. A declared store that is unreachable is a misconfiguration, not a reason to degrade.
- For a Kubernetes ConfigMap **mounted as files**, add the mount path to `VERTX_CONFIG_LOCATIONS`
  instead — no declaration needed. The `configmap` store type is for ConfigMaps read through the
  Kubernetes API, typically when the application has no filesystem access to the mount.

---

## Placeholders

Once the merged tree is assembled, every string value in it is scanned for `${...}` references and
resolved. Resolution runs once, eagerly, before `Vertx` is created.

### Grammar

| Syntax | Meaning |
|--------|---------|
| `${key}` | Resolve `key` through the chain below. No default — fail-closed if unresolved. |
| `${key:default}` | Bare-colon default: the **first top-level colon** (at brace depth 1, not inside a nested `${}`) splits key from default. The entire suffix is the default text, so `${endpoint:https://collector:4317}` has key `endpoint` and default `https://collector:4317`. `${key:}` yields an empty-string default. |
| `\${` | Escape: emits the literal text `${`. A `\` not followed by `${` is a literal backslash. |

Multiple placeholders in one value concatenate as strings:
`jdbc:postgresql://${db.host}:${db.port}/app`.

### Resolution Chain

For each key the engine consults, in order:

1. **Tree probe** — flat-key probe first (finds `DB_PASSWORD` as stored flat by the env store), then a
   dot-path walk descending nested `JsonObject`s for a key like `db.host`. A JSON `null` counts as
   not-found. The tree already reflects the full env / system-property / `--conf` hierarchy.
2. **Declared property sources**, in list order; the first non-empty hit wins. Source values are
   **literal** — a `${...}` inside a secret value passes through unchanged.
3. **Default** — if every chain step missed and a default was declared, the default text is itself
   resolved and may contain placeholders, bounded by a maximum nesting depth of 5.
4. **Failure** — the reference is recorded and the walk continues, so all failures are reported
   together.

The chain is tree-first. That preserves the env/system-property override story — a `-Dkey=value` wins
without touching the vault — and enables the self-reference idiom.

### Self-Reference Idiom

```json
{
  "db": {
    "password": "${db.password}"
  }
}
```

When the engine begins resolving `db.password` and meets another reference to `db.password` already on
the resolution stack, it treats the recursive tree probe as not-found and falls through to the declared
property sources. This is the recommended way to pull a secret from a vault without writing the value
into any config file. The reference fails only when the entire chain — tree (skipped by
self-reference) plus every declared source plus the default — is exhausted.

### Type Preservation

When a config value is exactly one placeholder token with no surrounding text, the resolved JSON type
is preserved: a tree `Integer` stays `Integer`, a tree `JsonObject` stays `JsonObject`, and a resolved
container is itself walked for nested placeholders. In a concatenation context every part is
stringified. Property-source values are always strings.

### Invariants & Gotchas

- **Fail-closed.** A reference with no default that exhausts the chain aborts startup. The engine
  collects every failure across the whole tree walk and throws a single
  `PlaceholderResolutionException` carrying a sorted, de-duplicated list of unresolved renderings —
  key names and chain strings only, never resolved values.
- **A source error is not a miss.** An auth failure, network error, or malformed response aborts
  startup immediately. Source errors are never degraded into not-found and are never masked by a
  default.
- **Resolved values never appear anywhere observable** — not in logs at any level, not in exception
  messages, not in engine `toString()`. The engine logs placeholder keys and counts only.
  Implementations of `ConfigPropertySource` and `ConfigPropertySourceFactory` must hold the same line.
- A genuine cycle (`a: "${b}"`, `b: "${a}"`) surfaces as two unresolved references with chain
  renderings such as `a -> b -> a` rather than a dedicated cycle message. The depth bound of 5 is the
  backstop against unbounded recursion.
- **Bootstrap subtrees may use tree references only.** Both `config.stores` and
  `config.propertySources` are resolved against the merged tree *before* any source exists, so a
  reference in either that needs a property source fails with a "tree references only" message. There
  is no source-on-source recursion.

---

## Property-Source SPI (`config.propertySources`)

Declare property sources to pull secrets from external backends into placeholder references.
Declarations are a JSON array; list order is lookup precedence among sources.

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
| *(other fields)* | — | Factory-specific config, passed as-is to `ConfigPropertySourceFactory.create` |

An unknown `type` aborts startup with a message naming the required module dependency.

### Interfaces

```java
public interface ConfigPropertySource extends AutoCloseable {
    String name();
    Optional<String> lookup(String key);  // throws ConfigPropertySourceException (unchecked) on error
    default void close() {}
}
```

```java
public interface ConfigPropertySourceFactory {
    String type();
    ConfigPropertySource create(String name, JsonObject sourceConfig);
}
```

Factories are discovered via `ServiceLoader`; register in
`META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory`.

### Not-Found vs Error Contract

| Outcome | Signal | Effect |
|---|---|---|
| Key absent from this source | Return `Optional.empty()` | Resolution continues to the next source |
| Unrecoverable error (auth, network, malformed response) | Throw `ConfigPropertySourceException` | Startup aborts immediately |

An exception message MUST contain the source name and key, and MUST NOT contain any resolved value.

### Invariants & Gotchas

- Sources are created at bootstrap in declared order and closed at shutdown in **reverse** declaration
  order. `close()` MUST NOT throw — an exception there is a programming error.
- Results are memoized per `(source, key)` during bootstrap: `lookup` is called at most once per key
  per source.
- Sources are consulted only during single-threaded bootstrap, so no thread-safety contract is
  required of an implementation.

### Extension Source Dependencies

| Type | Required artifact |
|------|-------------------|
| `"vault"` | `dev.vertique:vertique-config-vault` |
| `"aws-secrets"` | `dev.vertique:vertique-config-aws-secrets` |
| `"azure-keyvault"` | `dev.vertique:vertique-config-azure-keyvault` |

---

## Config Parsing

Config parsing is exposed as the injected `ConfigParser` interface, declared in
`dev.vertique.core.config` (`dev.vertique:vertique-core`). This artifact supplies the implementation,
its dedicated `ObjectMapper`, and the Dagger binding.

### ConfigParsingModule

Abstract Dagger `@Module` providing the single `ConfigParser` binding. **It must be listed in every
application `@Component`**, alongside `VertxModule`. There is no transitive auto-include: the only
universally installed framework modules live in `dev.vertique:vertique-core`, which cannot depend on
this artifact. Omitting it yields a Dagger missing-binding compile error.

```java
@Component(modules = {VertxModule.class, ConfigParsingModule.class, /* … */})
interface AppComponent { /* … */ }
```

A feature module that injects `ConfigParser` depends only on `dev.vertique:vertique-core`; Dagger
resolves the binding from `ConfigParsingModule` at the application layer. There is exactly one
`ConfigParser` binding in a correctly wired graph.

Typical use at a module's config boundary:

```java
@Provides @Singleton
static RestConfig restConfig(@VertxConfig JsonObject config, ConfigParser parser) {
    return parser.parse(JsonConfigPaths.navigateObject(config, "rest"), RestConfig.class);
}
```

### @ConfigMapper

`@ConfigMapper` (also declared in `dev.vertique.core.config`) is the optional `@Qualifier` for an
application-supplied `ObjectMapper` that customizes config parsing. Bind one anywhere in the
component; `ConfigParsingModule` picks it up through `@BindsOptionalOf` and re-layers the framework's
mandatory modules and lenient policy over it.

```java
@Provides @ConfigMapper
static ObjectMapper appConfigMapper() {
    return JsonMapper.builder().addModule(new MyConfigModule()).build();
}
```

Absent any `@ConfigMapper` binding, the framework uses its own lenient default mapper.

### Invariants & Gotchas

- **The config mapper is isolated** from Vert.x's `DatabindCodec.mapper()` and from any REST or JSON
  profile mapper. It registers `Jdk8Module`, `JavaTimeModule`, `KeyedCollectionModule` (from
  `dev.vertique:vertique-json`), and Vert.x's Jackson module; it coerces scalars leniently and
  tolerates unknown properties, independent of any strictness the REST-input mapper is given.
- **An `@ConfigMapper` override is finalized in place**, not copied — the framework layers its
  mandatory modules and lenient policy onto the very instance supplied and then owns it. Do not share
  that instance concurrently for another purpose.
- **A validation error from a config record's own compact constructor propagates as-is**, keeping its
  value-free `ConfigurationException` message. A raw Jackson type-mismatch is wrapped in a new
  `ConfigurationException` with a generic, value-free message so config values never leak.
- **Never catch `JsonProcessingException` from `ConfigParser`** — it is always wrapped into
  `ConfigurationException` before surfacing.

---

## Key Classes

### BootstrapConfigLoader

Performs the pre-`Vertx` two-phase synchronous bootstrap load. Called by
`VertiqueApplication.createVertxBuilder`; not normally called directly by application code.

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

The caller owns the returned sources from that point on, and closes them at shutdown in reverse order.

### ConfigBootstrap

Static helper exposing the default `ConfigRetrieverOptions` chain. Two constants name the directory
lookup: `CONFIG_LOCATIONS_ENV` (`"VERTX_CONFIG_LOCATIONS"`) and `DEFAULT_CONFIG_DIR` (`"config"`).

| Member | Status | Purpose |
|---|---|---|
| `defaultOptions()` | Current | The default chain: file dirs → env → sys |
| `defaultOptions(List<ConfigStoreOptions>)` | Current | The default chain with declared stores inserted before env/sys |
| `load(Vertx, JsonObject)` | Deprecated | Legacy async bridge; returns `Future<Result>` |
| `load(Vertx, JsonObject, ConfigRetrieverOptions)` | Deprecated | Same, with caller-supplied options |
| `record Result(JsonObject config, ConfigRetriever retriever)` | — | Legacy load result |

### ConfigModule

Deprecated concrete Dagger `@Module` that provides `ConfigRetriever` as an injectable singleton, taking
the retriever through its constructor. It exists for the legacy path only; omit it entirely when using
`VertiqueApplication`.

---

## Bridging an async retriever before component construction

`ConfigBootstrap.load(vertx, config())` resolves the configuration tree and hands back both the resolved
config and the retriever, so a component that needs the retriever can be built once the load completes.

Under `VertiqueApplication` the resolved tree is already in `config()`, so neither
`ConfigBootstrap.load` nor `ConfigModule` is needed — construct the component directly from `config()`.
Application startup is delegated to the lifecycle runner in `dev.vertique:vertique-application`; a host
that deploys verticles itself bypasses the startup phases and the steps that run in them.

If a legacy `MainVerticle` is deployed under `VertiqueApplication`, the bootstrap runs twice. The
second load merges the already-resolved tree against itself — a benign no-op with no correctness
impact. No detection or marker key is needed.

---

## Failure Taxonomy

Every failure raised during the bootstrap load maps to exit code `ExitCodes.VERTX_INITIALIZATION` (11)
via `VertiqueApplication`.

| Exception | Stage | Raised when |
|---|---|---|
| `BootstrapConfigException` | Bootstrap | Load timeout, I/O error, unknown store or source type, invalid declaration, or factory instantiation failure |
| `PlaceholderResolutionException` | Bootstrap | An unresolvable placeholder reference — in a bootstrap subtree ("tree references only") or in the full-tree pass |
| `ConfigPropertySourceException` | Bootstrap | An unrecoverable source lookup error during full-tree resolution |
| `IllegalStateException` | Bootstrap | `BootstrapConfigLoader.load` was called from a Vert.x event-loop or worker thread |
| `ConfigurationException` | Parsing | A typed config record rejected its own values, or a section failed to bind. Raised at Dagger provider time, so it fails component construction rather than the bootstrap load. |

When a factory fails partway through source instantiation, every already-created source is closed in
reverse order before the exception propagates.

---

## Extension Points

### ConfigPropertySourceFactory

Provide a `ConfigPropertySource` implementation for a custom secret or property backend:

1. Implement `ConfigPropertySourceFactory` — return the type key from `type()` and create instances
   from `create(name, sourceConfig)`.
2. Register it in `META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory`.
3. Declare instances under `config.propertySources` in application config.

Two public helpers support a factory implementation:

| Helper | Purpose |
|---|---|
| `SourceConfigValues` | Reads and validates a field from the source's `JsonObject` config, applying a default when absent and throwing `ConfigPropertySourceException` naming the source and field when the value is invalid |
| `SecretDataFlattener` | Flattens a nested `Map<String, Object>` into dot-joined `Map<String, String>` keys under an optional prefix, skipping nulls and preserving insertion order |

### ConfigBootstrap.defaultOptions(List<ConfigStoreOptions>)

An application that needs a custom retriever outside the standard bootstrap can build on the default
chain:

```java
ConfigRetrieverOptions opts = ConfigBootstrap.defaultOptions(List.of(
    new ConfigStoreOptions()
        .setType("http")
        .setConfig(new JsonObject().put("host", "config-server").put("port", 8888))
));
// Use opts with a ConfigRetriever created on a live Vertx instance
```

### @ConfigMapper ObjectMapper

Bind an `@ConfigMapper ObjectMapper` in the application component to customize the mapper backing
`ConfigParser`. The framework re-layers its mandatory modules and lenient policy over the supplied
instance before first use — see [Config Parsing](#config-parsing) above.

---

## Dependencies

| Artifact | Scope | Purpose |
|---|---|---|
| `dev.vertique:vertique-core` | compile | `ConfigParser` interface, `@ConfigMapper` qualifier, `JsonConfigPaths`, `ConfigurationException` |
| `dev.vertique:vertique-json` | compile | `KeyedCollectionModule` — keyed-collection deserialization on the config mapper |
| `io.vertx:vertx-config` | compile | `ConfigRetriever`, the built-in store types, and the `ConfigStoreFactory` SPI |
| `com.fasterxml.jackson.core:jackson-databind` | compile | `ObjectMapper` behind the config parser |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | compile | `Jdk8Module` — mandatory config-mapper module |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | compile | `JavaTimeModule` — mandatory config-mapper module |
| `com.google.dagger:dagger` | compile | `@Module`, `@Provides`, `@BindsOptionalOf` |
| `jakarta.inject:jakarta.inject-api` | compile | `@Singleton` |
| `org.slf4j:slf4j-api` | compile | Bootstrap logging |
| `org.projectlombok:lombok` | provided | Compile-time only |
