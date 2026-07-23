// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.azurekeyvault;

/**
 * Immutable connection, auth, and timeout settings for an Azure Key Vault gateway instance.
 *
 * <p>This record carries everything needed to build a configured Azure SDK
 * {@link com.azure.security.keyvault.secrets.SecretClient} and credential. It does not carry
 * resolved secret values — only structural configuration. The {@code clientId} field identifies
 * a managed identity and is not a secret (it is a well-known Azure resource ID).
 *
 * <p>This record is package-private: it is an internal transfer object between the factory and
 * the gateway, not part of the public API.
 *
 * @param endpoint          the Azure Key Vault endpoint URL as configured (e.g.
 *                          {@code https://myvault.vault.azure.net}); required, non-blank;
 *                          validated absolute URI with scheme http/https and a host
 * @param canonicalEndpoint the canonical log-safe form of the endpoint: {@code scheme://host}
 *                          or {@code scheme://host:port} — path, query, fragment, and userinfo
 *                          are stripped. Safe to log without leaking credential artifacts.
 * @param prefix            optional key prefix; when non-{@code null}, this source only serves
 *                          keys that start with the prefix, stripped before the vault lookup
 * @param authMethod        the authentication method ({@code "default"} or
 *                          {@code "managed-identity"}); never {@code null}
 * @param clientId          optional Azure client ID for narrowing managed identity selection;
 *                          {@code null} means rely on the SDK's own
 *                          {@code AZURE_CLIENT_ID} env-var resolution
 * @param connectTimeoutMs  connection timeout in milliseconds; must be positive; default 5000
 * @param readTimeoutMs     read/response timeout in milliseconds; must be positive; default 5000
 */
record AzureConnectionSettings(
        String endpoint,
        String canonicalEndpoint,
        String prefix,
        String authMethod,
        String clientId,
        int connectTimeoutMs,
        int readTimeoutMs) {}
