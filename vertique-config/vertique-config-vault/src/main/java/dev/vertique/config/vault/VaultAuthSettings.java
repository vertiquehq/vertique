// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

/**
 * Sealed hierarchy of parsed Vault authentication configurations.
 *
 * <p>The three supported authentication methods map to the three sealed permits:
 * <ul>
 *   <li>{@link Token} — static token from config or {@code VAULT_TOKEN} environment variable</li>
 *   <li>{@link Kubernetes} — Kubernetes service-account JWT login</li>
 *   <li>{@link AppRole} — AppRole {@code role_id} + {@code secret_id} login</li>
 * </ul>
 *
 * <p>This sealed interface is package-private: auth settings are an internal implementation
 * detail of {@code vertique-config-vault} and are not exposed to callers of the SPI.
 */
sealed interface VaultAuthSettings
        permits VaultAuthSettings.Token, VaultAuthSettings.Kubernetes, VaultAuthSettings.AppRole {

    /**
     * Token-based authentication.
     *
     * <p>The token is always resolved by the factory ({@link VaultPropertySourceFactory}) before
     * this record is constructed: if no explicit {@code auth.token} is present in config, the
     * factory reads the {@code VAULT_TOKEN} environment variable and fails startup when it is
     * absent. The Vault driver's ambient resolution ({@code ~/.vault-token}, etc.) is never
     * engaged. As a result, {@code token} is always non-null and non-blank when this record is
     * passed to the gateway.
     *
     * @param token the static Vault token resolved by the factory; non-null, non-blank
     */
    record Token(String token) implements VaultAuthSettings {

        /** Redacted representation — the token must never appear in logs or messages (NFR-CONF-002). */
        @Override
        public String toString() {
            return "Token[token=***]";
        }
    }

    /**
     * Kubernetes service-account JWT authentication.
     *
     * @param role    the Kubernetes auth role configured in Vault (required, non-blank)
     * @param jwtPath the filesystem path to the service-account JWT token; defaults to
     *                {@code /var/run/secrets/kubernetes.io/serviceaccount/token}
     */
    record Kubernetes(String role, String jwtPath) implements VaultAuthSettings {}

    /**
     * AppRole authentication.
     *
     * @param roleId   the AppRole role ID (required, non-blank)
     * @param secretId the AppRole secret ID (required, non-blank)
     */
    record AppRole(String roleId, String secretId) implements VaultAuthSettings {

        /** Redacted representation — credentials must never appear in logs or messages (NFR-CONF-002). */
        @Override
        public String toString() {
            return "AppRole[roleId=***, secretId=***]";
        }
    }
}
