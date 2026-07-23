<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Config Azure Key Vault Module

> **Status:** Alpha
> **Package:** `dev.vertique.config.azurekeyvault`
> **Artifact:** `vertique-config-azure-keyvault`
> **Depends on:** config

Provides an Azure Key Vault property source for the `config.propertySources` placeholder chain. Unlike the eager-load model used by `vertique-config-vault` and `vertique-config-aws-secrets`, this source is **on-demand**: only keys that are actually referenced as `${...}` placeholders are ever fetched. No `list` permission is needed, and secrets that are never referenced cause no SDK calls.

The module integrates with the `ConfigPropertySourceFactory` SPI and is discovered automatically via `ServiceLoader` — no Dagger module is required.

---

## When To Use It

Install `vertique-config-azure-keyvault` when secrets stored in Azure Key Vault must be injected into the application config tree at startup via `${key}` placeholder references. Pair it with `vertique-config` (already a transitive dependency) and `vertique-launcher` for full bootstrap integration.

Use the self-reference idiom (see Core Concepts) to pull vault secrets into the tree without writing any value in a config file. Use prefix routing to direct specific key namespaces to specific vault instances.

---

## Core Concepts

### On-Demand Lookup

Unlike the sibling vault and AWS Secrets sources, this source does **not** eagerly load all secrets at construction time. Instead, each `lookup(key)` call fetches the secret on demand. The bootstrap engine memoizes per `(source, key)`, so each secret is fetched at most once per bootstrap run regardless of how many times the same placeholder appears in the config tree.

The consequence is that the `SecretClient` is built once at source creation time, but auth errors do not surface until the first vault call.

### Lookup Pipeline

Each call to `lookup(key)` runs the following five steps in order:

1. **Prefix filter** — if a prefix is configured and the key does not start with it, returns `Optional.empty()` immediately without calling the SDK.
2. **Prefix strip** — the prefix is removed from the key.
3. **Dot-to-dash normalization** — `'.'` characters are replaced with `'-'`.
4. **Name validation** — if the normalized name does not match `[0-9a-zA-Z-]{1,127}`, returns `Optional.empty()` without calling the SDK. Azure Key Vault does not support names outside this character set.
5. **Gateway call** — `SecretClient.getSecret(normalizedName)` is issued. HTTP 404 → `Optional.empty()`. Any other failure → `ConfigPropertySourceException` (fail-closed).

Steps 1 and 4 short-circuit without any SDK call. This means keys from other config namespaces (environment variables, tree keys, keys headed for a different source) are silently skipped with no vault I/O.

### Engine Memoization

The bootstrap engine memoizes results per `(source, key)` — `lookup` is called at most once per distinct key per source instance. Multiple `${db.password}` references in the config tree trigger exactly one vault call.

### Fail-Closed Contract

Any SDK failure that is not HTTP 404 throws `ConfigPropertySourceException`, naming the source and the original placeholder key but never the secret value. Bootstrap aborts immediately. The placeholder default (`:default`) does not apply when the source throws — errors are never degraded into not-found.

### Self-Reference Idiom

The recommended pattern for pulling a vault secret into the config tree without writing any value in a config file:

```json
{
  "db": {
    "password": "${db.password}"
  },
  "config": {
    "propertySources": [
      {
        "name":     "akv-app",
        "type":     "azure-keyvault",
        "endpoint": "https://myapp.vault.azure.net",
        "prefix":   "db."
      }
    ]
  }
}
```

When the resolution engine encounters `${db.password}` and `db.password` is already on the resolution stack, the recursive tree probe is skipped. Resolution falls through to this source. The source strips the `"db."` prefix to get `"password"`, which is a valid vault secret name, and fetches it from Azure Key Vault.

---

## Configuration Reference

The full per-source configuration block under `config.propertySources[*]`:

```json
{
  "name":             "akv-app",
  "type":             "azure-keyvault",
  "endpoint":         "https://myapp.vault.azure.net",
  "prefix":           "db.",
  "auth": {
    "method":         "managed-identity",
    "clientId":       "my-user-assigned-mi-client-id"
  },
  "connectTimeoutMs": 5000,
  "readTimeoutMs":    5000
}
```

### Top-Level Fields

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `type` | Yes | — | Must be `"azure-keyvault"` |
| `name` | No | `azure-keyvault[{index}]` | Source instance name for diagnostics |
| `endpoint` | Yes | — | Azure Key Vault endpoint URL (e.g. `https://myapp.vault.azure.net`). Non-blank. |
| `prefix` | No | none | Optional key prefix. When set, only keys starting with this prefix reach the vault. The prefix is stripped before the lookup — routing multiple sources via non-overlapping prefixes is the standard way to separate vault namespaces. |
| `auth` | No | `{"method": "default"}` | Auth configuration object; see Auth Methods below. When absent entirely, defaults to `method: "default"` with no `clientId`. |
| `connectTimeoutMs` | No | 5000 | Connection timeout in milliseconds. Must be > 0. Applied via `HttpClientOptions` to the underlying Netty client. |
| `readTimeoutMs` | No | 5000 | Read and response timeout in milliseconds. Must be > 0. Applied as both `readTimeout` and `responseTimeout` via `HttpClientOptions`, bounding per-chunk read time and total time-to-first-byte from the server. |

### Auth Methods

#### `"default"` (Default Azure Credential Chain)

Uses `DefaultAzureCredentialBuilder`. Tries multiple credential sources in sequence: environment variables (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_CLIENT_SECRET`), workload identity, managed identity, Visual Studio Code, Azure CLI, etc.

```json
"auth": { "method": "default" }
```

With an optional `clientId`, narrows managed identity selection within the default chain:

```json
"auth": { "method": "default", "clientId": "my-client-id" }
```

When `clientId` is absent and the method is `"default"`, the SDK's own `AZURE_CLIENT_ID` environment variable applies for managed identity disambiguation — no framework-level override.

#### `"managed-identity"` (Managed Identity Only)

Uses `ManagedIdentityCredentialBuilder`. Bypasses the full default credential chain and authenticates using managed identity only.

```json
"auth": { "method": "managed-identity" }
```

With an optional `clientId`, selects a specific user-assigned managed identity:

```json
"auth": { "method": "managed-identity", "clientId": "my-user-assigned-mi-client-id" }
```

**Note:** Credential construction does not make any network calls. Auth errors surface at the first vault lookup, not at source creation time.

Any other `method` value is rejected at create time with a `ConfigPropertySourceException` naming the invalid value.

---

## Key Classes

### AzureKeyVaultPropertySourceFactory

`public class AzureKeyVaultPropertySourceFactory implements ConfigPropertySourceFactory`

The factory discovered by `ServiceLoader`. Registered in `META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory` with type key `"azure-keyvault"`.

| Method | Description |
|--------|-------------|
| `type()` | Returns `"azure-keyvault"` |
| `create(String name, JsonObject sourceConfig)` | Parses and validates the source config, builds an `AzureConnectionSettings`, constructs an `SdkKeyVaultGateway`, and returns an `AzureKeyVaultPropertySource` for on-demand lookup. Throws `ConfigPropertySourceException` on validation failure (missing/blank endpoint, unknown auth method, non-positive timeout). |

#### Invariants and Gotchas

- Schema validation happens before the `SecretClient` is built. A malformed config is rejected with a descriptive error before any Azure SDK construction occurs.
- The `SecretClient` is built once at `create()` time. SDK errors that surface at build time (e.g. malformed endpoint URL) fail the source immediately. Auth errors surface at the first vault call.
- `create()` is called from the single-threaded bootstrap path; no thread-safety is required.
- Unknown `auth.method` values are rejected at create time. Only `"default"` and `"managed-identity"` are accepted.

### AzureKeyVaultPropertySource

`class AzureKeyVaultPropertySource implements ConfigPropertySource` (package-private)

Executes the five-step lookup pipeline on every `lookup(String key)` call. The gateway is created once at construction time and reused for all lookups.

| Method | Description |
|--------|-------------|
| `name()` | Returns the source instance name |
| `lookup(String key)` | Runs the prefix-filter → strip → normalize → validate → gateway pipeline. Returns `Optional.empty()` when the key is filtered out, normalized to an invalid name, or the vault returns 404. Throws `ConfigPropertySourceException` on any other vault failure. Never logs or surfaces secret values. |
| `close()` | No-op. `SecretClient` has no `close()` — connections are managed by the Azure SDK's internal Netty connection pool, which is GC-managed. |

Logs the source name and endpoint at `INFO` level on construction. Never logs key values or secret content.

### SdkKeyVaultGateway

`class SdkKeyVaultGateway implements KeyVaultGateway` (package-private)

Production gateway backed by the Azure SDK `SecretClient`. The client is built once via `buildClient(AzureConnectionSettings)` and reused across all lookups.

Timeouts are applied via `HttpClientOptions` passed to `HttpClient.createDefault(httpOptions)` — the only effective path for configuring the underlying Netty HTTP client. `connectTimeout` and `readTimeout` bound the TCP-connect and per-read-chunk phases respectively. `responseTimeout` is additionally set to `readTimeoutMs` to bound the total time-to-first-byte from the server (the Azure SDK default is 60 seconds, which would stall bootstrap on auth misconfiguration). Passing the same `HttpClientOptions` to `SecretClientBuilder.clientOptions(...)` is redundant when an explicit HTTP client is supplied and is intentionally omitted.

The fail-closed contract: `ResourceNotFoundException` (HTTP 404) → `Optional.empty()`. Any other `HttpResponseException` or runtime exception → `ConfigPropertySourceException` naming the source and original placeholder key. The SDK exception cause is intentionally **not** attached — it is severed to prevent any potential credential or value content in the SDK's exception chain from leaking (NFR-CONF-002).

**Null-response guard**: Azure SDK credential exceptions (`CredentialUnavailableException`, `ClientAuthenticationException`) extend `HttpResponseException` but are constructed with a `null` `HttpResponse`. Without an explicit null guard, `e.getResponse().getStatusCode()` would throw a `NullPointerException` inside the catch block on auth misconfiguration. When the response is `null`, the exception's class simple name is used as the error detail instead of the HTTP status code.

---

## Registration

The module is registered as a `ConfigPropertySourceFactory` extension via `ServiceLoader`. No Dagger module is needed.

**`META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory`** contains:

```
dev.vertique.config.azurekeyvault.AzureKeyVaultPropertySourceFactory
```

To use the source, add `vertique-config-azure-keyvault` to the application's `pom.xml` and declare an `azure-keyvault` entry under `config.propertySources`:

```json
{
  "config": {
    "propertySources": [
      {
        "name":     "akv-app",
        "type":     "azure-keyvault",
        "endpoint": "https://myapp.vault.azure.net",
        "prefix":   "db."
      }
    ]
  }
}
```

The source config subtree is resolved in pass 1 (tree-only), so `endpoint` and `auth.clientId` may themselves be `${...}` references to environment variables or tree keys — as long as they resolve without needing a property source.

---

## Testing

The IT (`AzureKeyVaultPropertySourceIT`) uses [Lowkey Vault](https://github.com/nagyesta/lowkey-vault) (`nagyesta/lowkey-vault:7.3.0` via Testcontainers) as the Azure Key Vault test double. Lowkey Vault serves HTTPS with a self-signed certificate on port 8443.

The production `SdkKeyVaultGateway.buildClient(AzureConnectionSettings)` path (which uses `DefaultAzureCredential` / `ManagedIdentityCredential` with real TLS) is **not** exercised against Lowkey Vault by design — those credential types require a real Azure endpoint or a local CLI session. Instead, the IT injects a pre-built `SecretClient` (with the container's trust store and dummy basic auth) via the package-private `SdkKeyVaultGateway(String, SecretClient)` test-seam constructor and the `AzureKeyVaultPropertySourceFactory(BiFunction)` gateway-factory seam.

The IT covers the full lookup pipeline: prefix filtering, dot-to-dash normalization, name validation, 404-vs-error semantics, disabled-secret fail-closed behavior, `PlaceholderResolver` integration (including the self-reference idiom), and redaction (no sentinel values in logs or exception chains).

---

## Security Notes

NFR-CONF-002 applies throughout this module:

- Secret values **never** appear in log output at any level, in exception messages, or in `toString()` of any internal type.
- Exception messages contain only: source instance name, secret name (not value), original placeholder key, HTTP status codes, and structural descriptions of the failure. The SDK exception cause chain is severed at the gateway boundary.
- Credentials are resolved via the Azure SDK credential chain. They are never configured inline in the source declaration and never appear in error messages.
- TLS verification is **never** disabled in production code. The TLS relaxation (Apache HTTP client with self-signed-cert trust, `disableChallengeResourceVerification`) lives only in the IT and is injected entirely through the gateway-factory seam — it never touches the production builder path.

---

## Dependencies

- `dev.vertique:vertique-config-core` — `ConfigPropertySource`, `ConfigPropertySourceFactory`, `ConfigPropertySourceException` SPIs
- `com.azure:azure-security-keyvault-secrets:${azure-keyvault-secrets.version}` — Azure Key Vault Secrets SDK client
- `com.azure:azure-identity:${azure-identity.version}` — Azure SDK credential types (`DefaultAzureCredentialBuilder`, `ManagedIdentityCredentialBuilder`)
- `io.vertx:vertx-core` — `JsonObject` (config parsing)

The two Azure SDK artifacts bring `azure-core-http-netty` as a transitive dependency, which provides the Netty-based HTTP client used for timeout configuration. No Dagger runtime dependency. No Vert.x web dependency.

---

## Related ADRs

- ADR-0096: Bootstrap Config Relocation and `config.stores` Two-Phase Load — establishes the two-phase bootstrap model and the distinction between merge-based `config.stores` and lookup-based `config.propertySources`; this module implements the latter.
- ADR-0097: Placeholder Grammar and Progressive Resolution Chain — defines the `${key}` grammar, tree-first chain order, self-reference fall-through, fail-closed source-error contract, and the three-pass model that this module's on-demand lookup strategy satisfies.
