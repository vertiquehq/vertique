// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vertique.rest.core.RestConfigurationException;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.PfxOptions;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * SSL/TLS configuration for the HTTP server. Nested inside {@link HttpConfig} under the
 * {@code "ssl"} key.
 *
 * <p>Supports JKS, PKCS12, and PEM keystore formats. When {@code enabled} is {@code false}
 * (the default), all other fields are ignored and no SSL is configured on the server.
 *
 * <p>Example configuration:
 *
 * <pre>{@code
 * {
 *   "http": {
 *     "ssl": {
 *       "enabled": true,
 *       "keyStorePath": "/path/to/keystore.jks",
 *       "keyStorePassword": "changeit",
 *       "clientAuth": "NONE"
 *     }
 *   }
 * }
 * }</pre>
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class SslConfig {

    // --- Enabled ---

    /** Whether SSL/TLS is enabled. Defaults to {@code false}. */
    @Builder.Default
    private final boolean enabled = false;

    // --- Key/cert store ---

    /**
     * Path to the keystore file (JKS or PKCS12) or PEM private key file.
     * For PEM format, this is the private key path; use {@link #certPath} for the certificate.
     */
    private final String keyStorePath;

    /** Password for the keystore or private key. */
    private final String keyStorePassword;

    /**
     * Keystore format. Supported values: {@code "JKS"}, {@code "PKCS12"}, {@code "PEM"}.
     * Defaults to {@code "JKS"}.
     */
    @Builder.Default
    private final String keyStoreType = "JKS";

    /**
     * Path to the PEM certificate file. Only used when {@link #keyStoreType} is {@code "PEM"}.
     * When {@code null} and type is PEM, defaults to {@link #keyStorePath} (combined key+cert file).
     */
    private final String certPath;

    // --- Trust store (mTLS) ---

    /** Path to the trust store file for client certificate validation (mTLS). */
    private final String trustStorePath;

    /** Password for the trust store. */
    private final String trustStorePassword;

    /**
     * Trust store format. Supported values: {@code "JKS"}, {@code "PKCS12"}, {@code "PEM"}.
     * Defaults to {@code "JKS"}.
     */
    @Builder.Default
    private final String trustStoreType = "JKS";

    // --- Client auth ---

    /**
     * Client authentication mode. Supported values: {@code "NONE"}, {@code "REQUEST"},
     * {@code "REQUIRED"}. Defaults to {@code "NONE"}.
     */
    @Builder.Default
    private final String clientAuth = "NONE";

    // --- Protocol control ---

    /**
     * Set of TLS protocol versions to enable. Defaults to {@code {"TLSv1.2", "TLSv1.3"}}.
     */
    @Builder.Default
    private final Set<String> enabledProtocols = Set.of("TLSv1.2", "TLSv1.3");

    /**
     * Whether to enable ALPN (Application-Layer Protocol Negotiation) for HTTP/2 support.
     * Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean useAlpn = false;

    /**
     * Whether to enable SNI (Server Name Indication) for virtual hosting with multiple
     * certificates. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean sni = false;

    // --- Mapping ---

    /**
     * Applies this SSL configuration to the given {@link HttpServerOptions}. Does nothing
     * if {@link #enabled} is {@code false}.
     *
     * <p>Configures SSL/TLS, ALPN, SNI, protocol versions, key/cert store, trust store,
     * and client authentication mode. The {@link #keyStoreType} and {@link #trustStoreType}
     * determine which Vert.x key options implementation is used ({@link JksOptions},
     * {@link PfxOptions}, or {@link PemKeyCertOptions}).
     *
     * @param opts the HTTP server options to configure
     * @throws RestConfigurationException if {@link #clientAuth} is not a valid
     *         {@link ClientAuth} enum name
     */
    public void applyTo(HttpServerOptions opts) {
        if (!enabled) {
            return;
        }

        opts.setSsl(true).setUseAlpn(useAlpn).setSni(sni).setEnabledSecureTransportProtocols(enabledProtocols);

        // Key/cert store
        if (keyStorePath != null) {
            String type = keyStoreType != null ? keyStoreType : "JKS";
            switch (type.toUpperCase()) {
                case "PEM" -> {
                    String cert = certPath != null ? certPath : keyStorePath;
                    opts.setKeyCertOptions(
                            new PemKeyCertOptions().setKeyPath(keyStorePath).setCertPath(cert));
                }
                case "PKCS12" ->
                    opts.setKeyCertOptions(
                            new PfxOptions().setPath(keyStorePath).setPassword(keyStorePassword));
                default ->
                    opts.setKeyCertOptions(
                            new JksOptions().setPath(keyStorePath).setPassword(keyStorePassword));
            }
        }

        // Trust store (mTLS)
        if (trustStorePath != null) {
            String type = trustStoreType != null ? trustStoreType : "JKS";
            switch (type.toUpperCase()) {
                case "PEM" -> opts.setTrustOptions(new PemTrustOptions().addCertPath(trustStorePath));
                case "PKCS12" ->
                    opts.setTrustOptions(
                            new PfxOptions().setPath(trustStorePath).setPassword(trustStorePassword));
                default ->
                    opts.setTrustOptions(
                            new JksOptions().setPath(trustStorePath).setPassword(trustStorePassword));
            }
        }

        // Client auth
        String authValue = clientAuth != null ? clientAuth : "NONE";
        try {
            opts.setClientAuth(ClientAuth.valueOf(authValue.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new RestConfigurationException(
                    "Invalid ssl.clientAuth value '" + authValue + "'. Valid values: NONE, REQUEST, REQUIRED");
        }
    }
}
