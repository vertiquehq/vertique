<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Security Config Module

> **Status:** Beta
> **Package:** `dev.vertique.security.config`
> **Artifact:** `vertique-security-config`
> **Depends on:** security-core, config-core

Config/YAML-backed authorization adapters for the Vertique security model. This module reads the `authorization` section of the application config and contributes, via Dagger `@IntoSet` multibinding, a `PolicyDefinitionSource` and a `RolePolicyResolver` into the sets declared by `dev.vertique:vertique-security-runtime`'s `SecurityAuthzModule`.

The SPI contracts live in `dev.vertique:vertique-security-core`. The engine that consumes these contributions lives in `dev.vertique:vertique-security-runtime`.

---

## When To Use It

Include `vertique-security-config` when the application defines authorization policies and role→policy mappings in its config file rather than in code. An application using config-backed authorization includes **both** this module and `SecurityAuthzModule` in its Dagger component:

```java
@Component(modules = {
    VertxModule.class,
    ConfigModule.class,
    SecurityAuthzModule.class,   // declares the multibinding sets + builds the engine
    SecurityEventsModule.class,
    AuthzConfigModule.class,     // contributes config-backed PolicyDefinitionSource + RolePolicyResolver
    // ... application action contributors
})
interface AppComponent { ... }
```

`AuthzConfigModule` does not declare the `Authorizer` or `AuthorizationIntrospector` bindings — those remain owned by `SecurityAuthzModule`. This module only feeds config-derived entries into the engine's multibinding sets.

---

## Config Path

The module reads the `authorization` section of the root application config:

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

When the `authorization` section is absent, the module falls back to `AuthorizationConfig.defaults()` (no role mappings, no inline policies).

---

## Core Concepts

### Startup Validation

Both contributed components are validated at startup (fail-fast), in sequence, governed by the dependency graph:

1. **Policy patterns vs. action registry.** After `SecurityAuthzModule` builds the `ActionRegistry`, it calls `PolicyDefinitionSource.validateAgainst(ActionRegistry)` polymorphically on every contributed source — including `ConfigBackedPolicyDefinitionSource`. Exact action patterns must be registered; wildcard patterns must match at least one registered action. An offending pattern aborts startup.
2. **Role→policy name vs. merged catalogue.** `AuthzConfigModule.configBackedRolePolicyResolver(...)` validates every policy name referenced in `authorization.rolePolicies` against the **merged** `Set<PolicyDefinitionSource>` (union of all contributed sources' policies). A mapping that references a policy contributed programmatically through another source is accepted; an unknown policy name aborts startup with an `IllegalStateException` naming the offending role and policy.

This two-step ordering ensures cross-source references (a config-backed role mapping pointing at a programmatically-contributed policy) are accepted, while fully unknown references fail fast.

---

## Key Classes

### AuthzConfigModule

Abstract Dagger `@Module` that is the primary entry point for this module. Provides three bindings:

| Provided binding | Scope | Description |
|-----------------|-------|-------------|
| `AuthorizationConfig` | `@Singleton` | Parsed authorization config from the `authorization` section |
| `PolicyDefinitionSource` (`@IntoSet`) | `@Singleton` | Config-backed policy source; not validated here — validation is polymorphic via `SecurityAuthzModule` |
| `RolePolicyResolver` (`@IntoSet`) | `@Singleton` | Config-backed role resolver; validated against the merged policy catalogue before binding |

The `configBackedRolePolicyResolver` provider holds the merged-catalogue validation logic — it collects all policy names from every contributed `PolicyDefinitionSource` and checks each name in `authorization.rolePolicies` against that set. This is the only provider in the module that takes `Set<PolicyDefinitionSource>` as an argument, which is intentional: only the wiring layer can see the merged set.

---

### AuthorizationConfig

Root config record for the `authorization` section.

```java
public record AuthorizationConfig(
    Map<String, List<String>> rolePolicies,  // role → list of policy names
    List<PolicyDefinitionConfig> policies    // inline policy definitions
)
```

Both fields are defensively deep-copied at construction (the map values are also immutably copied). `rolePolicies` is a true user-defined dictionary (rule R9-compliant): keys are operator-defined role names, values are open lists of policy identifiers.

`AuthorizationConfig.fromJson(...)` fills defaults for omitted fields (empty map / empty list). `AuthorizationConfig.defaults()` returns the canonical empty config.

---

### PolicyDefinitionConfig

Config record for a single named authorization policy.

```java
public record PolicyDefinitionConfig(
    String name,                         // non-null, non-blank
    List<PolicyStatementConfig> statements
)
```

Compact constructor validates non-null, non-blank `name`. `statements` defaults to an empty list when absent in config.

---

### PolicyStatementConfig

Config record for a single authorization policy statement.

```java
public record PolicyStatementConfig(
    Effect effect,          // defaults to Effect.ALLOW when absent in config
    List<String> actions    // action pattern strings, e.g. "cms.content.read" or "cms.*"
)
```

The `actions` strings are converted to `ActionPattern` instances by `ConfigBackedPolicyDefinitionSource` at construction time. Invalid patterns (violating the `ActionRef` segment grammar, or a bare `"*"`) fail at that conversion.

---

### ConfigBackedPolicyDefinitionSource

`@Singleton` implementation of `PolicyDefinitionSource`. Converts `AuthorizationConfig.policies()` to `PolicyDefinition` objects at construction time:

- `PolicyDefinitionConfig` → `PolicyDefinition(name, statements)`
- `PolicyStatementConfig` → `PolicyStatement(effect, Set<ActionPattern>)`
- Action pattern strings → `ActionPattern` (grammar-validated at construction)

`validateAgainst(ActionRegistry)` is inherited from the `PolicyDefinitionSource` default and validates all patterns against the registry polymorphically. The fluent `withRegistry(ActionRegistry)` convenience method chains the same check and returns `this`.

---

### ConfigBackedRolePolicyResolver

`@Singleton` implementation of `RolePolicyResolver`. Reads its role→policy mappings from `AuthorizationConfig.rolePolicies()`. Policy-name existence is not validated here — the wiring layer (`AuthzConfigModule.configBackedRolePolicyResolver`) handles that against the merged catalogue.

```java
@Override
public Set<String> policiesForRoles(Set<String> roles) {
    // Returns the union of all policy names mapped to any of the supplied roles.
    // Unknown roles contribute nothing (silently ignored).
}
```

The package-private `rolePolicies()` accessor is used by `AuthzConfigModule` during the startup validation step to iterate the role→policy mapping.

---

## Extension Points

This module contributes into the SPIs declared by `SecurityAuthzModule`. Applications add their own contributions alongside the config-backed ones:

```java
// Contribute a programmatic policy source in addition to config-backed ones
@Provides @IntoSet
static PolicyDefinitionSource myPolicies(MyProgrammaticPolicySource s) { return s; }

// Contribute a programmatic role resolver in addition to config-backed one
@Provides @IntoSet
static RolePolicyResolver myResolver(MyRolePolicyResolver r) { return r; }
```

Config-backed and programmatic contributions are merged by `SecurityAuthzModule`. Role mappings in config that reference a policy contributed programmatically are validated against the merged catalogue (not just the config-backed policies), so cross-source references work.

---

## Dependencies

- `dev.vertique:vertique-security-core` — `PolicyDefinitionSource`, `RolePolicyResolver`, `ActionRegistry`, `PolicyDefinition`, `PolicyStatement`, `ActionPattern`, `Effect`
- `dev.vertique:vertique-config-core` — `ConfigParser`, `JsonConfigPaths` (parse boundary), `@VertxConfig`
