// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.azurekeyvault;

import dev.vertique.config.source.ConfigPropertySourceException;
import java.util.Optional;

/**
 * Internal seam between the Azure Key Vault property source and the Azure SDK.
 *
 * <p>All SDK calls are isolated behind this interface so that unit tests can inject a stub
 * without a live Azure Key Vault endpoint. The single method performs an on-demand lookup for
 * a single secret by its normalized name.
 *
 * <p>This interface is package-private: it is an internal detail of the
 * {@code vertique-config-azure-keyvault} module and must not be exposed to callers.
 *
 * <h2>Fail-Closed Contract</h2>
 * <p>Implementations are fail-closed:
 * <ul>
 *   <li>Secret not found (HTTP 404 / {@link com.azure.core.exception.ResourceNotFoundException})
 *       → return {@link Optional#empty()}</li>
 *   <li>Any other failure (auth, network, 403) → throw {@link ConfigPropertySourceException}
 *       naming the source and the {@code originalKey}; MUST NOT include the secret value or
 *       any credential in the exception message or cause chain.</li>
 * </ul>
 *
 * <h2>Argument Contract</h2>
 * <p>{@code normalizedName} is pre-validated by the caller to match
 * {@code [0-9a-zA-Z-]{1,127}}. Implementations may assume it is a valid Azure Key Vault secret
 * name and need not re-validate. {@code originalKey} is the original placeholder key (before
 * prefix stripping and normalization) and is used only for error message context.
 */
interface KeyVaultGateway {

    /**
     * Retrieves the secret value for the given normalized Azure Key Vault secret name.
     *
     * @param normalizedName the Azure Key Vault secret name (pre-validated as
     *                       {@code [0-9a-zA-Z-]{1,127}}); never {@code null}
     * @param originalKey    the original placeholder key before normalization; used only in
     *                       error messages — never a secret value
     * @return {@link Optional#empty()} if the secret does not exist in the vault (HTTP 404),
     *         or an {@link Optional} containing the secret value string
     * @throws ConfigPropertySourceException if the lookup fails for any reason other than
     *                                        not-found (auth failure, network error, 403, etc.);
     *                                        message names the source and {@code originalKey}
     *                                        but MUST NOT include the secret value
     */
    Optional<String> getSecret(String normalizedName, String originalKey);
}
