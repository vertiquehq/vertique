// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

import java.util.Map;

/**
 * Internal seam between the Vault property source and the jopenlibs driver.
 *
 * <p>All driver calls are isolated behind this interface so that unit tests can inject a stub
 * without a live Vault instance. Implementations must be thread-safe if used from multiple
 * threads, though in practice they are only called from single-threaded bootstrap.
 *
 * <p>This interface is package-private: it is an internal detail of the
 * {@code vertique-config-vault} module and must not be exposed to callers.
 */
interface VaultGateway {

    /**
     * Reads the secret at the given logical path and returns its key-value data.
     *
     * <p>Callers supply the <em>logical</em> path without the {@code /data/} infix (e.g.
     * {@code "secret/app"}, not {@code "secret/data/app"}). The KV v2 envelope is unwrapped
     * before this method returns.
     *
     * <p>The returned map contains the raw values as returned by the secret engine — not yet
     * prefixed or further flattened. String values are returned as-is; a JSON-object string
     * stored as a value is never re-interpreted.
     *
     * <p>Implementations are <strong>fail-closed</strong>: a path that cannot be read for any
     * reason (not found, permission denied, network error) MUST throw {@link VaultReadException}.
     * Returning an empty map for a declared path is not permitted — an empty return is reserved for
     * a path that genuinely exists but has no keys stored in it.
     *
     * @param path the logical Vault path to read; never {@code null} or blank
     * @return a non-null map of key → value for the secret at {@code path}; may be empty only if
     *         the secret exists but has no stored keys
     * @throws VaultReadException if the path is not found, permission is denied, any non-2xx HTTP
     *                            status is received, or a driver-level error occurs
     */
    Map<String, Object> readPath(String path);
}
