// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

/**
 * Immutable connection, authentication, and timeout settings for a Vault gateway instance.
 *
 * <p>This record carries everything the {@link JOpenLibsVaultGateway} needs to build a configured
 * Vault driver instance and authenticate. It intentionally does <em>not</em> carry path
 * declarations — those belong to {@link VaultSourceSettings} and are owned by
 * {@link VaultPropertySource}. This separation keeps the gateway focused on transport and auth.
 *
 * <p>This record is package-private: it is an internal transfer object between the factory and
 * the gateway, not part of the public API.
 *
 * @param address       the Vault server base URL (required, non-blank)
 * @param namespace     optional Vault Enterprise namespace; {@code null} if not set
 * @param authSettings  the resolved authentication configuration
 * @param openTimeoutMs connection open timeout in milliseconds (driver wants seconds — convert at
 *                      use site with {@code Math.max(1, (int) Math.ceil(ms / 1000.0))})
 * @param readTimeoutMs read timeout in milliseconds (same conversion applies)
 */
record VaultConnectionSettings(
        String address, String namespace, VaultAuthSettings authSettings, int openTimeoutMs, int readTimeoutMs) {}
