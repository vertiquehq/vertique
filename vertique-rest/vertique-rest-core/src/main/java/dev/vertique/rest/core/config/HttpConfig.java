// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.vertique.rest.core.RestConfigurationException;
import io.vertx.core.http.HttpServerOptions;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * HTTP server configuration for the framework. Deserialized from the {@code "http"} section
 * of the application config JSON and mapped comprehensively to {@link io.vertx.core.http.HttpServerOptions}.
 *
 * <p>All fields have sensible defaults matching Vert.x and HTTP RFC conventions. Applications
 * override individual fields by providing them in the {@code "http"} section of their config:
 *
 * <pre>{@code
 * {
 *   "http": {
 *     "port": 8443,
 *     "maxBodySize": 4194304,
 *     "uploadsDirectory": "file-uploads",
 *     "compressionSupported": true,
 *     "idleTimeoutSeconds": 30,
 *     "ssl": {
 *       "enabled": true,
 *       "keyStorePath": "/path/to/keystore.jks",
 *       "keyStorePassword": "changeit"
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>The {@code maxBodySize} and {@code uploadsDirectory} fields are consumed by {@link
 * io.vertx.ext.web.handler.BodyHandler} via {@code JaxRsRouterMount}. {@code maxBodySize} bounds
 * the total request body; {@code maxFormAttributeSize} and {@code maxFormFields} independently bound
 * decoded form content, by attribute size and by part count. {@code uploadsDirectory} selects where
 * multipart upload temporary files are spooled.
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class HttpConfig {

    // --- Core ---

    /** HTTP port to listen on. Defaults to {@code 8080}. */
    @Builder.Default
    private final int port = 8080;

    /** Network interface to bind to. Defaults to {@code "0.0.0.0"} (all interfaces). */
    @Builder.Default
    private final String host = "0.0.0.0";

    /**
     * Maximum allowed request body size in bytes. Enforced by
     * {@link io.vertx.ext.web.handler.BodyHandler#setBodyLimit(long)}, which returns 413
     * automatically when exceeded. Defaults to {@code 2097152} (2 MB).
     */
    @Builder.Default
    private final long maxBodySize = 2_097_152;

    /**
     * Directory for multipart upload temporary files, created by Vert.x {@link
     * io.vertx.ext.web.handler.BodyHandler} on demand. Must be non-blank. Defaults to {@code
     * "file-uploads"}.
     */
    @Builder.Default
    private final String uploadsDirectory = "file-uploads";

    // --- HTTP protocol ---

    /**
     * Whether HTTP response compression (gzip/deflate) is enabled. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean compressionSupported = false;

    /**
     * gzip/deflate compression level (1–9). Higher values compress more but use more CPU.
     * Defaults to {@code 6}.
     */
    @Builder.Default
    private final int compressionLevel = 6;

    /**
     * Whether HTTP request body decompression is enabled. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean decompressionSupported = false;

    /**
     * Maximum allowed length of all HTTP headers in bytes. Defaults to {@code 8192}.
     */
    @Builder.Default
    private final int maxHeaderSize = 8192;

    /**
     * Maximum allowed length of the HTTP initial request line (e.g. {@code "GET / HTTP/1.1"})
     * in bytes. Defaults to {@code 4096}.
     */
    @Builder.Default
    private final int maxInitialLineLength = 4096;

    /**
     * Whether the server automatically sends a 100 Continue response to clients that include
     * {@code Expect: 100-continue} in their request. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean handle100ContinueAutomatically = false;

    // --- Timeouts (seconds) ---

    /**
     * Idle timeout in seconds for connections with no traffic in either direction.
     * {@code 0} disables the timeout. Defaults to {@code 0}.
     */
    @Builder.Default
    private final int idleTimeoutSeconds = 0;

    /**
     * Read idle timeout in seconds. The connection is closed if no data is received within
     * this period. {@code 0} disables the timeout. Defaults to {@code 0}.
     */
    @Builder.Default
    private final int readIdleTimeoutSeconds = 0;

    /**
     * Write idle timeout in seconds. The connection is closed if no data is sent within
     * this period. {@code 0} disables the timeout. Defaults to {@code 0}.
     */
    @Builder.Default
    private final int writeIdleTimeoutSeconds = 0;

    // --- Network ---

    /**
     * Whether TCP keep-alive is enabled on server connections. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean tcpKeepAlive = false;

    /**
     * Size of the TCP accept backlog queue. {@code -1} uses the OS default. Defaults to {@code -1}.
     */
    @Builder.Default
    private final int acceptBacklog = -1;

    /**
     * Whether the server uses the PROXY protocol to read the real client IP from upstream
     * load balancers or proxies. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean useProxyProtocol = false;

    // --- Form limits ---

    /**
     * Maximum allowed size in bytes for a single URL-encoded form attribute value.
     * Defaults to {@code 8192}.
     */
    @Builder.Default
    private final int maxFormAttributeSize = 8192;

    /**
     * Maximum number of form parts allowed per request. Defaults to {@code 256}.
     *
     * <p>This is not limited to URL-encoded fields: the decoder counts <em>every</em> part of a
     * decoded body — {@code multipart/form-data} file parts and text parts as well as URL-encoded
     * attributes — against one shared limit. A multipart request carrying more parts than this is
     * therefore rejected during body decoding, before the resource method runs, even when its
     * total size is far below {@link #maxBodySize}.
     */
    @Builder.Default
    private final int maxFormFields = 256;

    // --- SSL/TLS (nested) ---

    /**
     * SSL/TLS configuration. Defaults to a disabled {@link SslConfig} instance.
     * When {@link SslConfig#enabled()} is {@code false}, no SSL is configured.
     */
    @Builder.Default
    private final SslConfig ssl = SslConfig.builder().build();

    /**
     * Lombok builder customization that preserves the upload-directory default while validating
     * both direct builder calls and Jackson's {@link Jacksonized} builder-deserialization path.
     */
    public static class HttpConfigBuilder {
        /**
         * Sets the multipart upload temporary-file directory.
         *
         * @param uploadsDirectory the non-blank upload directory
         * @return this builder
         * @throws RestConfigurationException when {@code uploadsDirectory} is null or blank
         */
        public HttpConfigBuilder uploadsDirectory(String uploadsDirectory) {
            String validated = uploadsDirectory;
            if (validated == null || validated.isBlank()) {
                throw new RestConfigurationException("http.uploadsDirectory must be non-blank");
            }
            this.uploadsDirectory$value = validated;
            this.uploadsDirectory$set = true;
            return this;
        }
    }

    // --- Mapping ---

    /**
     * Creates a fully-configured {@link HttpServerOptions} from this configuration.
     * Maps all core, protocol, timeout, network, form limit, and SSL settings.
     *
     * @return configured Vert.x HTTP server options
     * @throws RestConfigurationException if SSL configuration contains invalid values
     */
    public HttpServerOptions toHttpServerOptions() {
        HttpServerOptions opts = new HttpServerOptions()
                .setPort(port)
                .setHost(host)
                .setCompressionSupported(compressionSupported)
                .setCompressionLevel(compressionLevel)
                .setDecompressionSupported(decompressionSupported)
                .setMaxHeaderSize(maxHeaderSize)
                .setMaxInitialLineLength(maxInitialLineLength)
                .setHandle100ContinueAutomatically(handle100ContinueAutomatically)
                .setIdleTimeout(idleTimeoutSeconds)
                .setReadIdleTimeout(readIdleTimeoutSeconds)
                .setWriteIdleTimeout(writeIdleTimeoutSeconds)
                .setTcpKeepAlive(tcpKeepAlive)
                .setAcceptBacklog(acceptBacklog)
                .setUseProxyProtocol(useProxyProtocol)
                .setMaxFormAttributeSize(maxFormAttributeSize)
                .setMaxFormFields(maxFormFields);

        if (ssl != null) {
            ssl.applyTo(opts);
        }

        return opts;
    }
}
