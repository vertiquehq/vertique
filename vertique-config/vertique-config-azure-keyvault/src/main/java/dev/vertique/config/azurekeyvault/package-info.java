// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Azure Key Vault on-demand property source for bootstrap placeholder resolution.
 *
 * <h2>Overview</h2>
 * <p>This package provides a {@link dev.vertique.config.source.ConfigPropertySourceFactory}
 * implementation (type key: {@code "azure-keyvault"}) that resolves placeholder keys from
 * Azure Key Vault on demand during application bootstrap. It implements the
 * {@link dev.vertique.config.source.ConfigPropertySource} SPI defined in the
 * {@code vertique-config} module.
 *
 * <h2>On-Demand Lookup Model</h2>
 * <p>Unlike the AWS Secrets Manager source (which eagerly fetches all configured secrets at
 * startup), this source calls {@code getSecret(name)} per lookup. The Azure SDK
 * {@link com.azure.security.keyvault.secrets.SecretClient} is built once at
 * {@link dev.vertique.config.source.ConfigPropertySourceFactory#create(String,
 * io.vertx.core.json.JsonObject)} time — eagerly validating endpoint and auth configuration —
 * and reused for all subsequent lookups. The bootstrap engine memoizes results per
 * {@code (source, key)}, so each secret is fetched at most once.
 *
 * <h2>Credential Resolution</h2>
 * <p>Two auth methods are supported:
 * <ul>
 *   <li><strong>{@code "default"}</strong> (default when {@code auth} is absent or
 *       {@code method} is {@code "default"}) — uses
 *       {@link com.azure.identity.DefaultAzureCredentialBuilder}. This tries multiple credential
 *       sources in sequence (env vars, workload identity, managed identity, Azure CLI, etc.).
 *       An optional {@code clientId} narrows managed identity selection.</li>
 *   <li><strong>{@code "managed-identity"}</strong> — uses
 *       {@link com.azure.identity.ManagedIdentityCredentialBuilder}. An optional {@code clientId}
 *       selects a specific user-assigned managed identity.</li>
 * </ul>
 * <p>Credential objects are constructed at create time but do not make network calls until the
 * first {@code getSecret} call — any auth errors surface at the first lookup, not startup.
 * The source is fail-closed: an auth or network error at lookup time throws
 * {@link dev.vertique.config.source.ConfigPropertySourceException}; only a true 404 (key not
 * found in the vault) returns {@link java.util.Optional#empty()}.
 *
 * <h2>Key Normalization</h2>
 * <p>Azure Key Vault secret names only allow {@code [0-9a-zA-Z-]} (max 127 chars). Placeholder
 * keys are normalized before vault lookup:
 * <ol>
 *   <li>If a {@code prefix} is configured and the key does not start with it, returns
 *       {@link java.util.Optional#empty()} without calling the SDK.</li>
 *   <li>The prefix is stripped from the key.</li>
 *   <li>{@code '.'} characters in the remaining key are replaced with {@code '-'}.</li>
 *   <li>If the normalized name does not match {@code [0-9a-zA-Z-]{1,127}}, returns
 *       {@link java.util.Optional#empty()} without calling the SDK.</li>
 * </ol>
 *
 * <h2>Redaction Rule (NFR-CONF-002)</h2>
 * <p>Resolved secret values MUST NEVER appear in log messages, exception messages, or any other
 * observable output. Only source names, placeholder key names, and structural/diagnostic detail
 * strings are safe to include. Azure SDK error messages for {@code getSecret} failures are
 * HTTP-status-and-request-id shaped (generally safe), but exception causes are wrapped with a
 * message-only {@link dev.vertique.config.source.ConfigPropertySourceException} to sever any SDK
 * exception chain that might embed secret content.
 *
 * <h2>ServiceLoader Registration</h2>
 * <p>The factory is registered in
 * {@code META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory} so that the
 * bootstrap engine discovers it automatically via {@link java.util.ServiceLoader}.
 */
package dev.vertique.config.azurekeyvault;
