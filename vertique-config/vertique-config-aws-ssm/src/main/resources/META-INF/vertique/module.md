<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Config AWS SSM Module

> **Status:** Alpha
> **Package:** `dev.vertique.config.store.ssm`
> **Artifact:** `vertique-config-aws-ssm`
> **Depends on:** `io.vertx:vertx-config`, `software.amazon.awssdk:ssm`, `software.amazon.awssdk:url-connection-client`
> **Test-only:** `dev.vertique:vertique-config-core` (for `BootstrapConfigLoader` integration tests)

Provides an AWS SSM Parameter Store config **store** for the Vert.x `ConfigRetriever`. Unlike property sources, which resolve individual `${...}` placeholders on demand, this module merges a whole SSM parameter subtree into the config tree during phase 2 of `BootstrapConfigLoader`. It is registered via `ServiceLoader` under the type key `"aws-ssm"` and declared in the `config.stores` array.

> **Store vs property source:** Stores merge eagerly into the tree (last-writer-wins within the declared-store priority slot). Property sources (like `vertique-config-aws-secrets`) are consulted per-key only during `${...}` placeholder resolution (pass 3). Use SSM-as-store when you want a whole application config subtree from SSM; use property sources when you want to inject individual secrets by key reference.

---

## When To Use It

Install `vertique-config-aws-ssm` when application configuration is stored as a subtree of SSM Parameter Store parameters (e.g. `/myapp/prod/db/host`, `/myapp/prod/db/password`) that should be merged into the config tree during bootstrap. The store fetches the entire subtree once, maps parameter names to nested JSON keys, and contributes the result to the `config.stores` priority slot (above file directories, below env/sys, below `--conf`).

Use `vertique-config-aws-secrets` instead when you only need to inject individual AWS Secrets Manager secrets into `${...}` placeholder references.

---

## Core Concepts

### Parameter-Name to Nested-Key Mapping

The configured `path` prefix is stripped from each parameter name, and the remainder is split on `"/"` to produce nested JSON keys:

| SSM parameter name | path | Result key |
|---|---|---|
| `/myapp/prod/db/host` | `/myapp/prod/` | `{"db":{"host":"..."}}` |
| `/myapp/prod/db/password` | `/myapp/prod/` | `{"db":{"password":"..."}}` |
| `/myapp/prod/http/port` | `/myapp/prod/` | `{"http":{"port":"..."}}` |

Multiple parameters at the same depth are merged into a single nested object.

#### Name-Conflict Semantics

When two parameters map to conflicting key paths, the last-write-wins rule applies. AWS SSM does not guarantee delivery order within a single `GetParametersByPath` response page, so conflict resolution order is unspecified. Both directions produce a WARN log naming the conflicting parameter name (never the value):

| Conflict direction | What happens |
|---|---|
| **Leaf-after-subtree** — a parameter's name maps to a key that already exists as an intermediate object from a prior parameter | The leaf value overwrites the subtree; everything nested below that key is discarded. |
| **Subtree-after-leaf** — a parameter's path descends through a key that was previously set as a leaf value | The leaf is replaced with a new intermediate object to accommodate the descending path. |

Avoid conflicting parameter names in SSM. The WARN log makes conflicts observable, but the outcome is non-deterministic across re-fetches.

### Parameter Types

| SSM type | JSON representation |
|---|---|
| `String` | String value |
| `SecureString` | String value (decrypted when `withDecryption=true`, the default) |
| `StringList` | `JsonArray` of comma-split strings (whitespace trimmed per element) |

A `StringList` parameter with value `"a,b,c"` becomes `["a","b","c"]`.

### Prefix Re-rooting

When `prefix` is set, the entire parameter tree is nested under that dot-separated path before being contributed to the retriever chain:

- `prefix: "app"` → `{"app":{"db":{"host":"..."}}}`
- `prefix: "app.db"` → `{"app":{"db":{"db":{"host":"..."}}}}`

Use `prefix` when multiple SSM paths must be merged into the same config tree without key collision.

### Empty-Path Semantics

An empty parameter list (no parameters found under the configured path) returns a succeeded result carrying an empty `JsonObject`. A declared-but-empty path is not an error; the store contributes nothing to the merged tree. This also means a nonexistent path is not an error — `GetParametersByPath` returns an empty list for paths with no parameters.

### Fail-on-Connection-Error

While an empty path succeeds, a real connection or authentication failure (network unreachable, wrong credentials, wrong endpoint) causes `BootstrapConfigLoader.load` to fail with a `BootstrapConfigException`. Declared stores are non-optional: a store that cannot be reached is a misconfiguration that must abort startup.

### Recursive Fetching

When `recursive=true` (the default), the store fetches all parameters under the path regardless of depth. When `recursive=false`, only direct children of the path are returned (i.e. parameters with no additional `/` in their name after the path prefix).

### Credentials

Credentials are resolved via the AWS SDK default credential provider chain: environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`), Java system properties (`aws.accessKeyId`, `aws.secretAccessKey`), `~/.aws/credentials`, EC2/ECS instance metadata, and IAM roles. Credentials are never configured inline in the store declaration and never appear in error messages.

---

## Configuration Reference

The store entry goes in `config.stores[*]`:

```json
{
  "config": {
    "stores": [
      {
        "type": "aws-ssm",
        "config": {
          "path":             "/myapp/prod/",
          "region":           "eu-west-1",
          "endpointOverride": "http://localhost:4566",
          "recursive":        true,
          "withDecryption":   true,
          "prefix":           "app",
          "connectTimeoutMs": 5000,
          "readTimeoutMs":    5000
        }
      }
    ]
  }
}
```

### Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `path` | Yes | — | SSM path prefix, e.g. `/myapp/prod/`. Leading and trailing `/` are added if absent. |
| `region` | No | SDK default chain | AWS region string (e.g. `"eu-west-1"`). When absent, resolved via `AWS_DEFAULT_REGION`, `aws.region`, instance metadata, etc. |
| `endpointOverride` | No | none | Override the SSM endpoint URL. Use for LocalStack (`http://localhost:4566`) or other test endpoints. |
| `recursive` | No | `true` | Whether to fetch parameters recursively under `path`. |
| `withDecryption` | No | `true` | Whether to decrypt `SecureString` parameters. |
| `prefix` | No | none | Dot-separated re-root prefix (e.g. `"app"` or `"app.db"`) that nests the entire result under additional path segments. Must not be blank; must not contain blank dot-segments (e.g. `"a..b"` is invalid). |
| `connectTimeoutMs` | No | 5000 | Connection timeout in milliseconds. Must be positive. |
| `readTimeoutMs` | No | 5000 | Socket/read timeout in milliseconds. Must be positive. |

### Validation Errors

Thrown as `IllegalArgumentException` from the factory (surfaces as `BootstrapConfigException` in the bootstrap loader):

- `path` missing or blank
- `prefix` present but blank, or contains blank dot-segments (e.g. `"a..b"`, `".app"`, `"app."`)
- `connectTimeoutMs` or `readTimeoutMs` is zero, negative, or non-integer

### Path Normalization

The `path` value is normalized before use: a leading `/` is added if absent, and a trailing `/` is added if absent. The values `/myapp/prod`, `/myapp/prod/`, `myapp/prod`, and `myapp/prod/` all normalize to `/myapp/prod/`.

---

## Parameter-Name Mapping Examples

Given path `/myapp/prod/`:

| SSM parameter | SSM type | Result |
|---|---|---|
| `/myapp/prod/db/password` | SecureString | `{"db":{"password":"..."}}` |
| `/myapp/prod/db/host` | String | `{"db":{"host":"..."}}` |
| `/myapp/prod/features/list` | StringList `"a,b,c"` | `{"features":{"list":["a","b","c"]}}` |
| `/other/outside` | String | (absent — outside the path) |

Multiple parameters at the same nesting level are merged. For example, `/myapp/prod/db/host` and `/myapp/prod/db/port` both contribute to the `db` sub-object.

---

## Security Notes

NFR-CONF-002 applies throughout this module:

- Parameter values **never** appear in log output at any level, in exception messages, or in `toString()` of any internal type.
- Exception messages contain only: the configured `path` (an operator-declared config key, safe to log), parameter counts, field names, and structural descriptions. AWS SDK error messages are passed through as-is; SSM error bodies describe operational failures (permission denied, network timeout) and do not carry parameter values.
- `SecureString` parameters are decrypted by AWS SSM into the `GetParametersByPath` response when `withDecryption=true`. The decrypted values land in the config tree by design — the config tree is an in-process data structure, not a log or wire-format. The values are never logged by the store.
- The AWS SDK retains cause chains from `SdkClientException` and `AwsServiceException` because SSM error messages are path-shaped and name-shaped, not value-shaped. The SDK's own error messages do not carry parameter values; the module passes them through without inspection.
- `endpointOverride` (a URL, not a secret) is safe to include in error and log messages and is treated as a structural identifier.
- Credentials are resolved via the AWS SDK default credential provider chain. They are never configured inline, stored beyond the SDK client's internal lifecycle, or included in error messages.

---

## Key Classes

### SsmConfigStoreFactory

`public class SsmConfigStoreFactory implements ConfigStoreFactory`

The factory discovered by `ServiceLoader`. Registered in
`META-INF/services/io.vertx.config.spi.ConfigStoreFactory` with type key `"aws-ssm"`.

| Method | Description |
|--------|-------------|
| `name()` | Returns `"aws-ssm"` |
| `create(Vertx vertx, JsonObject configuration)` | Parses and validates the store config, builds `SsmStoreSettings`, creates an `SdkSsmGateway`, and returns an `SsmConfigStore`. Throws `IllegalArgumentException` on validation failure. |

### SsmConfigStore

`class SsmConfigStore implements ConfigStore` (package-private)

Performs paginated `GetParametersByPath` calls inside `Vertx.executeBlocking` to avoid blocking the event loop. Maps parameter names to a nested `JsonObject`, optionally applies prefix re-rooting, and returns the result as a JSON buffer.

| Method | Description |
|--------|-------------|
| `get()` | Issues SDK calls (via `SsmGateway`), builds the parameter tree, applies optional prefix, returns JSON buffer. Failed if the SDK throws. |
| `close()` | Closes the underlying `SdkSsmGateway` and its SDK client. |

### SdkSsmGateway

`class SdkSsmGateway implements SsmGateway, AutoCloseable` (package-private)

Production gateway backed by the AWS SDK v2 `SsmClient`. Uses the `url-connection-client` (no Apache HTTP dependency). Performs paginated `GetParametersByPath` calls, collecting all pages before returning. Closed by `SsmConfigStore.close()`.

---

## Registration

The module is registered as a `ConfigStoreFactory` extension via `ServiceLoader`. No Dagger module is needed.

**`META-INF/services/io.vertx.config.spi.ConfigStoreFactory`** contains:

```
dev.vertique.config.store.ssm.SsmConfigStoreFactory
```

To use the store, add `vertique-config-aws-ssm` to the application's `pom.xml` and declare an `aws-ssm` entry under `config.stores`:

```json
{
  "config": {
    "stores": [
      {
        "type": "aws-ssm",
        "config": {
          "path":   "/myapp/prod/",
          "region": "eu-west-1"
        }
      }
    ]
  }
}
```

The `config.stores` entry is resolved against the phase-1 tree before phase-2 runs, so `region`, `endpointOverride`, and other fields may themselves be `${...}` references to environment variables or tree keys — as long as they resolve without needing a property source.

---

## Dependencies

**Runtime / compile scope:**

- `io.vertx:vertx-config` — `ConfigStore`, `ConfigStoreFactory` SPIs, `ConfigRetriever`
- `io.vertx:vertx-core` — `Vertx.executeBlocking`, `Future`, `Buffer`, `JsonObject`, `JsonArray`
- `software.amazon.awssdk:ssm` — AWS SDK v2 SSM client (version managed via the `software.amazon.awssdk:bom` BOM at `${aws.sdk.version}` in the parent `<dependencyManagement>`)
- `software.amazon.awssdk:url-connection-client` — lightweight JDK-based HTTP client for the SDK; avoids an Apache HttpClient runtime dependency

**Test scope only:**

- `dev.vertique:vertique-config-core` — `BootstrapConfigLoader` and `BootstrapConfigException` used in end-to-end integration tests. Not required at runtime; main sources do not import it.

No Dagger runtime dependency.

---

## Related ADRs

- ADR-0096: Bootstrap Config Relocation and `config.stores` Two-Phase Load — establishes the two-phase bootstrap model, the `config.stores` declaration schema, the declared-store priority slot, and the non-optional store semantics that this module implements.
