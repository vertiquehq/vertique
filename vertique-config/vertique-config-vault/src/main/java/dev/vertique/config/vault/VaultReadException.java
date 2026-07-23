// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Thrown by {@link VaultGateway} implementations when a Vault read operation fails.
 *
 * <p>This is a package-private runtime exception used to surface driver-level failures and
 * HTTP-status errors at the gateway seam. The {@link VaultPropertySource} converts these into
 * {@link dev.vertique.config.source.ConfigPropertySourceException} before propagating to callers.
 *
 * <p>Two failure modes are represented:
 * <ul>
 *   <li><strong>Driver exception</strong> — the gateway catches a
 *       {@link io.github.jopenlibs.vault.VaultException} (e.g. TCP refused, TLS error, non-4xx
 *       HTTP error) and constructs this exception from a sanitized detail string built by
 *       {@link JOpenLibsVaultGateway#sanitizeDriverFailure(String, io.github.jopenlibs.vault.VaultException)}.
 *       Use {@link #VaultReadException(String, String)} for this case.</li>
 *   <li><strong>HTTP status error</strong> — 404 (path not found), 403 (permission denied), or
 *       any other non-2xx the driver silently returned as an empty response. Use
 *       {@link #VaultReadException(String, String)}.</li>
 * </ul>
 *
 * <h2>Cause-chain severing (NFR-CONF-002)</h2>
 * <p>The jopenlibs driver embeds raw HTTP response bodies in {@code VaultException} messages for
 * non-2xx reads and failed logins (format: {@code "...\nResponse body: <body>"}). An untrusted
 * proxy 502 HTML page or a Vault error body can therefore flow up the cause chain and reach
 * startup logs as unbounded, untrusted text. To match the severed-cause discipline applied by
 * {@code vertique-config-azure-keyvault} and {@code vertique-config-aws-secrets}, this exception
 * intentionally does NOT attach a driver throwable as a cause. The message contains only the
 * exception class simple name and, when available, the HTTP status code — both constructed by
 * this module, never from the driver message.
 *
 * <p>Messages MUST NOT contain any resolved secret value — only path names, HTTP status codes,
 * and structural descriptions of the failure.
 */
class VaultReadException extends ConfigurationException {

    /**
     * Constructs a gateway read exception with a caller-supplied safe detail string.
     *
     * <p>This constructor is the primary path for all failure modes: driver exceptions (whose
     * detail is sanitized externally by
     * {@link JOpenLibsVaultGateway#sanitizeDriverFailure(String, io.github.jopenlibs.vault.VaultException)}),
     * HTTP status failures (404, 403, other non-2xx), and JWT file read errors. The cause is
     * intentionally NOT attached — see class-level javadoc for NFR-CONF-002 rationale.
     *
     * @param path   the Vault path that was being read; used for diagnostics only
     * @param detail a safe, non-secret description of the failure (e.g. "HTTP 403",
     *               "VaultException HTTP 500", "IOException reading JWT from '/path/jwt'")
     */
    VaultReadException(String path, String detail) {
        super("Vault read failed for path '" + path + "': " + detail);
    }
}
