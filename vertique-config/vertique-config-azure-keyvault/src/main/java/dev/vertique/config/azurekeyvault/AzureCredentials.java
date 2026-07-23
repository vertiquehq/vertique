// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.azurekeyvault;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;

/**
 * Factory for Azure credential objects used by {@link SdkKeyVaultGateway}.
 *
 * <p>This class is package-private and contains a single static factory method that selects
 * and constructs the appropriate {@link TokenCredential} based on the configured auth method
 * and optional client ID. It exists as a separate class so that the credential-selection
 * logic can be unit-tested without an Azure endpoint — credential construction does not make
 * any network calls; auth errors only surface at the first actual vault operation.
 *
 * <h2>Supported Auth Methods</h2>
 * <ul>
 *   <li><strong>{@code "default"}</strong> — {@link DefaultAzureCredentialBuilder}. Tries
 *       multiple credential sources in sequence (environment variables, workload identity,
 *       managed identity, Azure CLI, etc.). If {@code clientId} is supplied, it is applied
 *       via {@link DefaultAzureCredentialBuilder#managedIdentityClientId(String)} to narrow
 *       managed identity selection.</li>
 *   <li><strong>{@code "managed-identity"}</strong> — {@link ManagedIdentityCredentialBuilder}.
 *       If {@code clientId} is supplied, it is applied via
 *       {@link ManagedIdentityCredentialBuilder#clientId(String)} to select a specific
 *       user-assigned managed identity.</li>
 * </ul>
 *
 * <h2>Credential Construction and Network Calls</h2>
 * <p>Neither {@link DefaultAzureCredentialBuilder#build()} nor
 * {@link ManagedIdentityCredentialBuilder#build()} makes any network call at construction time.
 * Auth errors are deferred until the credential is first used to acquire a token (i.e. at the
 * first vault lookup). This means endpoint or auth misconfiguration that the SDK validates
 * eagerly (e.g. a malformed endpoint URL in
 * {@link com.azure.security.keyvault.secrets.SecretClientBuilder}) will still fail at
 * create time, but auth credential errors will surface at first lookup.
 */
final class AzureCredentials {

    /** Auth method constant for the Azure Default credential chain. */
    static final String METHOD_DEFAULT = "default";

    /** Auth method constant for managed identity. */
    static final String METHOD_MANAGED_IDENTITY = "managed-identity";

    private AzureCredentials() {
        // utility class — no instances
    }

    /**
     * Builds and returns a {@link TokenCredential} for the given auth method and optional
     * client ID.
     *
     * <p>Construction does not make any network calls; auth errors surface at first vault
     * operation (fail-open for the credential object itself, fail-closed for the vault lookup
     * contract).
     *
     * @param authMethod the auth method string ({@code "default"} or
     *                   {@code "managed-identity"}); the caller must have validated that only
     *                   these two values are present (schema validation in the factory)
     * @param clientId   optional Azure client ID for narrowing managed identity selection;
     *                   {@code null} means rely on the SDK's own environment-variable resolution
     * @return a configured {@link TokenCredential}; never {@code null}
     */
    static TokenCredential build(String authMethod, String clientId) {
        if (METHOD_MANAGED_IDENTITY.equals(authMethod)) {
            ManagedIdentityCredentialBuilder builder = new ManagedIdentityCredentialBuilder();
            if (clientId != null) {
                builder.clientId(clientId);
            }
            return builder.build();
        }
        // "default" — DefaultAzureCredential (also the fallback for any unrecognised value,
        // but the factory rejects unknown methods at create time before reaching here)
        DefaultAzureCredentialBuilder builder = new DefaultAzureCredentialBuilder();
        if (clientId != null) {
            builder.managedIdentityClientId(clientId);
        }
        return builder.build();
    }
}
