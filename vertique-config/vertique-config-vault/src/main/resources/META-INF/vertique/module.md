<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Config Vault Module

> **Status:** Alpha
> **Package:** `dev.vertique.config.vault`
> **Artifact:** `vertique-config-vault`
> **Depends on:** config

Provides a HashiCorp Vault KV v2 property source for the `config.propertySources` placeholder chain. At construction time the module reads every declared Vault path, flattens the key-value data into an immutable in-memory map, and serves subsequent `lookup()` calls from that map with no further I/O. The module integrates with the `ConfigPropertySourceFactory` SPI and is discovered automatically via `ServiceLoader` — no Dagger module is required.

This module is not a Vert.x config store (`config.stores`). It is a property source consulted only during placeholder resolution (`${...}` pass 3). It never performs I/O after bootstrap.

---

## When To Use It

Install `vertique-config-vault` when secrets stored in HashiCorp Vault KV v2 must be injected into the application config tree at startup via `${key}` placeholder references. Pair it with `vertique-config` (already a transitive dependency) and `vertique-launcher` for full bootstrap integration.

Use the self-reference idiom (see Core Concepts) to pull Vault secrets into the tree without writing the value in any config file.

---

## Core Concepts

### Eager Load

All declared paths are read once, at source instantiation time during bootstrap phase 2 (source creation). The resulting map is immutable. A declared path that cannot be read — 404, 403, TCP-refused, or any other error — aborts startup immediately with a `ConfigPropertySourceException` naming the source and path. Placeholder defaults do not override this: if a source is declared and fails to load, bootstrap fails before pass 3 (placeholder resolution) runs.

### Path Semantics

Users supply KV v2 paths **without** the `/data/` infix. The jopenlibs driver is configured with `engineVersion(2)` and unwraps the KV v2 `data/data` envelope internally.

```
CORRECT:   "secret/app"
INCORRECT: "secret/data/app"
```

The path is the logical mount-relative path you see in the Vault UI or CLI (`vault kv get secret/app`), not the raw HTTP API path.

### Key Flattening and Prefix Model

The jopenlibs driver returns KV v2 secret data as a flat map: the keys are exactly the keys stored in the secret, and each value is a string. A JSON-object string stored as a secret value is preserved literally and is never re-interpreted or decomposed further.

The optional `prefix` field is prepended to every key read from that path. Keys read from Vault may themselves contain dots (e.g. `api.key`) — the dot is part of the key name and is not treated as a separator.

Example: Vault path `secret/app` containing two keys — `password` and `api.key` — with prefix `"app."` produces `app.password` and `app.api.key` in the resolved map.

When two paths produce the same prefixed key, the later path in the declaration order wins.

Note: the `VaultPropertySource` code contains a `flattenInto` helper that descends into nested `Map` structures. This applies to gateway implementations that return real nested maps (such as test stubs) but not to the production jopenlibs gateway, which always returns flat string maps.

### Self-Reference Idiom

The recommended pattern for pulling a Vault secret into the tree without writing the value in any config file:

```json
{
  "db": {
    "password": "${db.password}"
  },
  "config": {
    "propertySources": [
      {
        "name": "vault-app",
        "type": "vault",
        "address": "http://vault:8200",
        "paths": [{ "path": "secret/app", "prefix": "db." }]
      }
    ]
  }
}
```

When the resolution engine encounters `${db.password}` and the `db.password` key is already on the stack, the recursive tree probe is skipped. Resolution falls through to the Vault source, which supplies the value under the prefixed key `db.password`.

---

## Configuration Reference

The full per-source configuration block under `config.propertySources[*]`:

```json
{
  "name":           "vault-app",
  "type":           "vault",
  "address":        "http://vault:8200",
  "namespace":      "my-ns",
  "auth": {
    "method":       "token",
    "token":        "s.mytoken"
  },
  "paths": [
    { "path": "secret/app",    "prefix": "app." },
    { "path": "secret/shared", "prefix": "" }
  ],
  "openTimeoutMs":  5000,
  "readTimeoutMs":  5000
}
```

### Top-Level Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `type` | Yes | — | Must be `"vault"` |
| `name` | No | `vault[{index}]` | Source instance name for diagnostics |
| `address` | Yes | — | Vault base URL (e.g. `http://vault:8200`) |
| `namespace` | No | none | Vault Enterprise namespace |
| `auth` | No | token-from-env | Auth configuration object; see Auth Methods below |
| `paths` | Yes | — | Non-empty array of path entries |
| `openTimeoutMs` | No | 5000 | Connection open timeout in milliseconds |
| `readTimeoutMs` | No | 5000 | Read timeout in milliseconds |

**Timeout note:** The jopenlibs driver accepts timeouts in integer seconds. Both `openTimeoutMs` and `readTimeoutMs` are converted at build time via `Math.max(1, ceil(ms / 1000.0))`. Sub-1000 ms values map to 1 second (the driver treats zero as "no timeout").

### Auth Methods

When `auth` is absent entirely, token auth is assumed. The framework resolves the `VAULT_TOKEN` environment variable at create time; if it is absent, startup fails with a clear error. This is equivalent to `"method": "token"` with no `token` field.

#### Token

```json
"auth": { "method": "token", "token": "s.mytoken" }
```

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `method` | No | `"token"` | Auth method selector |
| `token` | No | `VAULT_TOKEN` env var | Static Vault token. If absent, the **framework** resolves the `VAULT_TOKEN` environment variable at create time and fails startup when it is missing; the Vault CLI token file `~/.vault-token` is never consulted. |

#### Kubernetes

```json
"auth": { "method": "kubernetes", "role": "my-app", "jwtPath": "/var/run/secrets/kubernetes.io/serviceaccount/token" }
```

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `method` | Yes | — | `"kubernetes"` |
| `role` | Yes | — | Vault Kubernetes auth role |
| `jwtPath` | No | `/var/run/secrets/kubernetes.io/serviceaccount/token` | Filesystem path to the service-account JWT token file |

#### AppRole

```json
"auth": { "method": "approle", "roleId": "my-role-id", "secretId": "my-secret-id" }
```

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `method` | Yes | — | `"approle"` |
| `roleId` | Yes | — | AppRole role ID |
| `secretId` | Yes | — | AppRole secret ID |

### Path Entry Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `path` | Yes | — | Logical Vault path — no `/data/` infix (e.g. `"secret/app"`) |
| `prefix` | No | `""` | Prefix prepended to all keys read from this path |

---

## Key Classes

### VaultPropertySourceFactory

`public class VaultPropertySourceFactory implements ConfigPropertySourceFactory`

The factory discovered by `ServiceLoader`. Registered in `META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory` with type key `"vault"`.

| Method | Description |
|--------|-------------|
| `type()` | Returns `"vault"` |
| `create(String name, JsonObject sourceConfig)` | Parses and validates the source config, authenticates against Vault, reads all declared paths, and returns a `VaultPropertySource`. Throws `ConfigPropertySourceException` on any validation, authentication, or read failure. |

#### Invariants and Gotchas

- `create()` is fail-closed: any declared path that returns 404 (not found), 403 (permission denied), a non-2xx HTTP status, or a TCP error throws `ConfigPropertySourceException` and aborts bootstrap. The default configured on a `${key:default}` placeholder is not used as a fallback.
- Timeouts are converted from milliseconds to seconds via ceiling division; values below 1000 ms round up to 1 second.
- `create()` is called from the single-threaded bootstrap path; no thread-safety is required.

### VaultPropertySource

`class VaultPropertySource implements ConfigPropertySource` (package-private)

Holds the immutable in-memory map built at construction time.

| Method | Description |
|--------|-------------|
| `name()` | Returns the source instance name |
| `lookup(String key)` | Map lookup — no I/O. Returns `Optional.empty()` for keys absent from the map |

Logs the count of loaded keys at INFO level on construction. Never logs key values.

---

## Extension and Registration

The module is registered as a `ConfigPropertySourceFactory` extension via `ServiceLoader`. No Dagger module is needed.

**`META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory`** contains:

```
dev.vertique.config.vault.VaultPropertySourceFactory
```

To use the source, add `vertique-config-vault` to the application's `pom.xml` and declare a `vault` entry under `config.propertySources`:

```json
{
  "config": {
    "propertySources": [
      {
        "name":    "vault-app",
        "type":    "vault",
        "address": "http://vault:8200",
        "paths":   [{ "path": "secret/app", "prefix": "app." }]
      }
    ]
  }
}
```

The source config subtree is resolved in pass 1 (tree-only), so `address` and `auth.token` may themselves be `${...}` references to environment variables or tree keys — as long as they resolve from the tree without needing a property source.

---

## Security Notes

NFR-CONF-002 applies throughout this module:

- Secret values **never** appear in log output at any level, in exception messages, or in `toString()` output of any internal type.
- Exception messages contain only: source instance name, path name, key name, HTTP status codes, and structural descriptions of the failure.
- The `auth.method` string is echoed in error messages (it is a structural identifier, not a secret). The `auth.token`, `auth.secretId`, and JWT file contents are never echoed.
- The jopenlibs driver returns 403 responses as an empty data map rather than throwing; this module inspects the HTTP status after every read and throws `ConfigPropertySourceException` for 403, ensuring a bad or revoked token aborts startup visibly rather than producing a silently empty source.

### Driver cause-chain severing

The jopenlibs driver embeds raw HTTP response bodies in `VaultException` messages for non-2xx reads and failed logins (format: `"...\nResponse body: <body>"`). An untrusted proxy 502 HTML page or a Vault error body can flow up the exception cause chain and reach startup logs as unbounded, untrusted text.

To match the severed-cause discipline of `vertique-config-azure-keyvault` and `vertique-config-aws-secrets`:

- Every caught `VaultException` is sanitized by `JOpenLibsVaultGateway.sanitizeDriverFailure(path, e)` before being rethrown as a `VaultReadException`.
- The sanitized detail contains only: `VaultException` class simple name + `"HTTP <code>"` when the driver set a non-zero status. The driver message is discarded.
- The driver `VaultException` is **not** attached as the cause — the chain is severed entirely.
- `VaultReadException` carries only the string-based `(path, detail)` constructor; no `Throwable`-carrying constructor exists, making accidental cause attachment a compile error.

---

## Dependencies

- `dev.vertique:vertique-config-core` — `ConfigPropertySource`, `ConfigPropertySourceFactory`, `ConfigPropertySourceException` SPIs
- `io.github.jopenlibs:vault-java-driver` — sync Vault HTTP driver (the community-maintained jopenlibs fork)
- `io.vertx:vertx-core` — `JsonObject`, `JsonArray` (config parsing)

No Dagger runtime dependency. No Vert.x web dependency.
