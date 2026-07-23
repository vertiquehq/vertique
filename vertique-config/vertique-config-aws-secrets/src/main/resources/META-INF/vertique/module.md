<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Config AWS Secrets Module

> **Status:** Alpha
> **Package:** `dev.vertique.config.awssecrets`
> **Artifact:** `vertique-config-aws-secrets`
> **Depends on:** config

Provides an AWS Secrets Manager property source for the `config.propertySources` placeholder chain. At construction time the module fetches every declared secret from Secrets Manager, builds an immutable in-memory map, and serves all subsequent `lookup()` calls from that map with no further I/O. The module integrates with the `ConfigPropertySourceFactory` SPI and is discovered automatically via `ServiceLoader` — no Dagger module is required.

This module is not a Vert.x config store (`config.stores`). It is a property source consulted only during placeholder resolution (`${...}` pass 3). It never performs I/O after bootstrap.

---

## When To Use It

Install `vertique-config-aws-secrets` when secrets stored in AWS Secrets Manager must be injected into the application config tree at startup via `${key}` placeholder references. Pair it with `vertique-config` (already a transitive dependency) and `vertique-launcher` for full bootstrap integration.

Use the self-reference idiom (see Core Concepts) to pull secrets into the tree without writing any value in a config file.

---

## Core Concepts

### Eager Load

All declared secrets are fetched once, at source construction time during bootstrap pass 2 (source creation). The resulting map is immutable. Any fetch failure — secret not found, access denied, binary secret, or network error — aborts startup immediately with a `ConfigPropertySourceException` naming the source and secret ID but never the secret value. Placeholder defaults do not override this: if a source is declared and fails to load, bootstrap fails before pass 3 (placeholder resolution) runs. The SDK client is closed immediately after the eager fetch; no further SDK calls occur.

### Entry Modes

Each entry in the `secrets` array is processed in one of two mutually exclusive modes. Exactly one of `prefix` or `key` must be present per entry — both or neither is a schema error.

**Prefix mode** (`"prefix": "db."`) — the secret's `SecretString` must be a JSON object. Its keys are flattened under the declared prefix using `SecretDataFlattener` from the `config` module. Nested JSON objects are flattened with dot-separators: a key `{"nested": {"host": "db-host"}}` stored under prefix `"db."` produces `db.nested.host`. If the `SecretString` is not a valid JSON object, startup is aborted with a `ConfigPropertySourceException` that names the secret ID but never its content.

**Key mode** (`"key": "api.token"`) — the secret's `SecretString` is stored as-is under exactly the declared key, regardless of whether it looks like JSON. The content is never inspected or parsed.

When two secrets produce the same resolved key, the later entry in the declaration order wins.

### Fail-Closed Contract

Binary secrets (`SecretBinary` set, `SecretString` null), missing secrets, access-denied responses, and all network or SDK errors all abort startup. None of these conditions degrades to empty-string or missing-key — partial configuration is a startup failure, not a silent default.

### Credentials

Credentials are resolved via the AWS SDK default credential provider chain: environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`), Java system properties (`aws.accessKeyId`, `aws.secretAccessKey`), `~/.aws/credentials`, EC2/ECS instance metadata, and IAM roles. Credentials are never configured inline in the source declaration and never appear in error messages.

### Self-Reference Idiom

The recommended pattern for pulling an AWS secret into the config tree without writing any value in a config file:

```json
{
  "db": {
    "password": "${db.password}"
  },
  "config": {
    "propertySources": [
      {
        "name": "aws-app",
        "type": "aws-secrets",
        "region": "eu-west-1",
        "secrets": [
          { "secretId": "prod/db-creds", "prefix": "db." }
        ]
      }
    ]
  }
}
```

When the resolution engine encounters `${db.password}` and the `db.password` key is already on the stack, the recursive tree probe is skipped. Resolution falls through to the AWS Secrets source, which supplies the value under the prefixed key `db.password`.

---

## Configuration Reference

The full per-source configuration block under `config.propertySources[*]`:

```json
{
  "name":             "aws-app",
  "type":             "aws-secrets",
  "region":           "eu-west-1",
  "endpointOverride": "http://localhost:4566",
  "connectTimeoutMs": 5000,
  "readTimeoutMs":    5000,
  "secrets": [
    { "secretId": "prod/db-creds",  "prefix": "db." },
    { "secretId": "prod/api-token", "key": "api.token" }
  ]
}
```

### Top-Level Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `type` | Yes | — | Must be `"aws-secrets"` |
| `name` | No | `aws-secrets[{index}]` | Source instance name for diagnostics |
| `region` | No | SDK default region chain | AWS region string (e.g. `"eu-west-1"`). When absent, the SDK resolves the region via `AWS_DEFAULT_REGION`, `aws.region`, instance metadata, etc. |
| `endpointOverride` | No | none | Override the Secrets Manager endpoint URL. Use for LocalStack (`http://localhost:4566`) or other test endpoints. |
| `connectTimeoutMs` | No | 5000 | Connection timeout in milliseconds, applied to `UrlConnectionHttpClient` |
| `readTimeoutMs` | No | 5000 | Socket/read timeout in milliseconds, applied to `UrlConnectionHttpClient` |
| `secrets` | Yes | — | Non-empty array of secret entry objects |

### Secret Entry Fields

Each element of the `secrets` array:

| Field | Required | Description |
|-------|----------|-------------|
| `secretId` | Yes | AWS Secrets Manager secret ID or full ARN. Non-blank. |
| `prefix` | Exactly one of `prefix`/`key` | Prefix prepended to all keys produced by flattening the JSON blob `SecretString`. Empty string `""` is allowed — keys appear without a prefix. |
| `key` | Exactly one of `prefix`/`key` | Single property key under which the plain `SecretString` is exposed as-is. |

**Validation errors** (thrown during bootstrap pass 2):
- Missing or empty `secrets` array
- Missing or blank `secretId`
- Both `prefix` and `key` present on the same entry
- Neither `prefix` nor `key` present on an entry

---

## Key Classes

### AwsSecretsPropertySourceFactory

`public class AwsSecretsPropertySourceFactory implements ConfigPropertySourceFactory`

The factory discovered by `ServiceLoader`. Registered in `META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory` with type key `"aws-secrets"`.

| Method | Description |
|--------|-------------|
| `type()` | Returns `"aws-secrets"` |
| `create(String name, JsonObject sourceConfig)` | Parses and validates the source config, builds connection settings, constructs an `SdkSecretsGateway`, and returns an `AwsSecretsPropertySource` that eagerly fetches all declared secrets. Throws `ConfigPropertySourceException` on any validation or fetch failure. |

#### Invariants and Gotchas

- `create()` is fail-closed: any declared secret that returns `SecretBinary` (not `SecretString`), is missing, or cannot be reached throws `ConfigPropertySourceException` and aborts bootstrap.
- Schema validation happens before any SDK call. A malformed `secrets` entry fails before the first network request is made.
- `create()` is called from the single-threaded bootstrap path; no thread-safety is required.

### AwsSecretsPropertySource

`class AwsSecretsPropertySource implements ConfigPropertySource` (package-private)

Holds the immutable in-memory map built at construction time. The SDK client is closed by this class immediately after all secrets are fetched; no further SDK I/O occurs during the source's lifetime.

| Method | Description |
|--------|-------------|
| `name()` | Returns the source instance name |
| `lookup(String key)` | Map lookup — no I/O. Returns `Optional.empty()` for keys absent from the map. |
| `close()` | No-op. The SDK client was already closed during construction. |

Logs the number of loaded keys and secrets at INFO level on construction. Never logs key values or secret content.

---

## Extension and Registration

The module is registered as a `ConfigPropertySourceFactory` extension via `ServiceLoader`. No Dagger module is needed.

**`META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory`** contains:

```
dev.vertique.config.awssecrets.AwsSecretsPropertySourceFactory
```

To use the source, add `vertique-config-aws-secrets` to the application's `pom.xml` and declare an `aws-secrets` entry under `config.propertySources`:

```json
{
  "config": {
    "propertySources": [
      {
        "name":    "aws-app",
        "type":    "aws-secrets",
        "region":  "eu-west-1",
        "secrets": [
          { "secretId": "prod/db-creds", "prefix": "db." }
        ]
      }
    ]
  }
}
```

The source config subtree is resolved in pass 1 (tree-only), so `region`, `endpointOverride`, and any other fields may themselves be `${...}` references to environment variables or tree keys — as long as they resolve without needing a property source.

---

## Security Notes

NFR-CONF-002 applies throughout this module:

- Secret values **never** appear in log output at any level, in exception messages, or in `toString()` of any internal type.
- Exception messages contain only: source instance name, secret IDs (structural identifiers, safe to echo), field names, structural descriptions, and the AWS SDK's own error message text. `GetSecretValue` error bodies describe operational failures (access-denied, not-found, etc.) and do not carry secret values; the module passes those messages through as-is.
- Binary secrets surface a descriptive error naming the secret ID and the reason (`binary secret; only SecretString secrets are supported`) with no content.
- Credentials are resolved via the AWS SDK default credential provider chain. They are never configured inline in the source declaration, stored in memory beyond the SDK client's internal lifecycle, or included in error messages.
- The `endpointOverride` field (a URL, not a secret) is safe to include in error and log messages. It is treated as a structural identifier.

---

## Dependencies

- `dev.vertique:vertique-config-core` — `ConfigPropertySource`, `ConfigPropertySourceFactory`, `ConfigPropertySourceException`, `SecretDataFlattener` SPIs
- `software.amazon.awssdk:secretsmanager` — AWS SDK v2 Secrets Manager client (version managed via `aws.sdk.version` BOM property in the parent POM)
- `software.amazon.awssdk:url-connection-client` — lightweight JDK-based HTTP client for the SDK; avoids an Apache HttpClient runtime dependency
- `io.vertx:vertx-core` — `JsonObject`, `JsonArray` (config parsing)

The two AWS SDK artifacts are governed by the `software.amazon.awssdk:bom` BOM, imported at version `${aws.sdk.version}` in the parent `<dependencyManagement>`. Application POMs do not need to declare SDK versions directly.

No Dagger runtime dependency. No Vert.x web dependency.

---

## Related ADRs

- ADR-0096: Bootstrap Config Relocation and `config.stores` Two-Phase Load — establishes the two-phase bootstrap model and the distinction between merge-based `config.stores` and lookup-based `config.propertySources`; this module implements the latter.
- ADR-0097: Placeholder Grammar and Progressive Resolution Chain — defines the `${key}` grammar, tree-first chain order, self-reference fall-through, fail-closed source-error contract, and the three-pass model that this module's eager-load strategy is designed to satisfy.
