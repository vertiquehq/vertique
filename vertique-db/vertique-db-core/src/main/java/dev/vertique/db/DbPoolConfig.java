// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Database connection pool configuration. Covers all Vert.x 5 {@code PoolOptions} and common
 * {@code SqlConnectOptions} fields.
 *
 * <p>Vendor modules (e.g., {@code db-postgresql}) map this to the appropriate {@code
 * SqlConnectOptions} and {@code PoolOptions} for pool construction.
 *
 * <p>Deserialized from the {@code "db"} section of the application config:
 *
 * <pre>{@code
 * {
 *   "db": {
 *     "host": "localhost",
 *     "port": 5432,
 *     "database": "mydb",
 *     "user": "app",
 *     "password": "secret",
 *     "maxPoolSize": 10
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Secret hygiene.</strong> {@code password}, {@code trustStorePassword}, and the vendor
 * {@code properties} bag are read from configuration but are {@link JsonProperty.Access#WRITE_ONLY}:
 * Jackson never serializes them back out, and {@link #toString()} renders them redacted, so a config
 * dump, diagnostics endpoint, or log line cannot reveal them.
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class DbPoolConfig {

    // --- Connection ---

    /** The database host. */
    private final String host;

    /** The database port. Vendor-specific defaults are set by the vendor module. */
    private final int port;

    /** The database name. */
    private final String database;

    /** The database user. */
    private final String user;

    /** The database password. */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private final String password;

    // --- Pool sizing ---

    /** Maximum number of connections in the pool. Defaults to {@code 5}. */
    @Builder.Default
    private final int maxPoolSize = 5;

    /**
     * Maximum number of requests waiting for a connection. {@code -1} means unbounded. Defaults to
     * {@code -1}.
     */
    @Builder.Default
    private final int maxWaitQueueSize = -1;

    /**
     * Number of event loop threads used by the pool. {@code 0} means use the Vert.x default.
     * Defaults to {@code 0}.
     */
    @Builder.Default
    private final int eventLoopSize = 0;

    // --- Pool timeouts (milliseconds) ---

    /**
     * Maximum time in milliseconds to wait for a connection from the pool. Defaults to {@code
     * 30000}.
     */
    @Builder.Default
    private final int connectionTimeoutMs = 30_000;

    /**
     * Maximum time in milliseconds a connection may remain idle before being evicted. {@code 0}
     * means no idle timeout. Defaults to {@code 0}.
     */
    @Builder.Default
    private final int idleTimeoutMs = 0;

    /**
     * Maximum lifetime in milliseconds of a connection in the pool. {@code 0} means no limit.
     * Defaults to {@code 0}.
     */
    @Builder.Default
    private final int maxLifetimeMs = 0;

    /**
     * Period in milliseconds at which the pool cleaner runs to evict idle/expired connections.
     * Defaults to {@code 1000}.
     */
    @Builder.Default
    private final int poolCleanerPeriodMs = 1_000;

    // --- Connection options ---

    /**
     * Whether to cache prepared statements on the connection level. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean cachePreparedStatements = false;

    /**
     * Maximum number of prepared statements to cache per connection. Defaults to {@code 256}.
     */
    @Builder.Default
    private final int preparedStatementCacheMaxSize = 256;

    /**
     * Number of reconnect attempts on connection failure. {@code 0} means no reconnect. Defaults to
     * {@code 0}.
     */
    @Builder.Default
    private final int reconnectAttempts = 0;

    /**
     * Time in milliseconds between reconnect attempts. Defaults to {@code 1000}.
     */
    @Builder.Default
    private final long reconnectIntervalMs = 1_000;

    // --- Vendor-specific ---

    /**
     * Vendor-specific connection properties (e.g., {@code ApplicationName} for PostgreSQL). Defaults
     * to an empty map.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Builder.Default
    private final Map<String, String> properties = Map.of();

    // --- SSL/TLS ---

    /**
     * SSL mode for the database connection. Supported values depend on the vendor driver.
     * For PostgreSQL: {@code DISABLE}, {@code ALLOW}, {@code PREFER}, {@code REQUIRE},
     * {@code VERIFY_CA}, {@code VERIFY_FULL}. Defaults to {@code "DISABLE"}.
     */
    @Builder.Default
    private final String sslMode = "DISABLE";

    /**
     * Whether to trust all SSL certificates without verification. Use only in development.
     * Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean trustAll = false;

    /**
     * Path to the trust store file (PEM, JKS, or PKCS12). The type is auto-detected from the
     * file extension unless {@link #trustStoreType} is set explicitly.
     */
    private final String trustStorePath;

    /**
     * Password for the trust store. Required for JKS and PKCS12 formats; ignored for PEM.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private final String trustStorePassword;

    /**
     * Explicit trust store type: {@code PEM}, {@code JKS}, or {@code PKCS12}. If not set, the
     * type is auto-detected from the {@link #trustStorePath} file extension.
     */
    private final String trustStoreType;

    /**
     * Path to the client private key file (PEM format). Required together with {@link #certPath}
     * for mutual TLS (mTLS).
     */
    private final String keyPath;

    /**
     * Path to the client certificate file (PEM format). Required together with {@link #keyPath}
     * for mutual TLS (mTLS).
     */
    private final String certPath;

    // --- Secret hygiene ---

    /**
     * Redacted rendering: {@code password}, {@code trustStorePassword}, and the vendor {@code
     * properties} bag are never included, so a log line or exception message can never reveal them.
     *
     * @return a redacted string rendering of this config
     */
    @Override
    public String toString() {
        return "DbPoolConfig[host=" + host
                + ", port=" + port
                + ", database=" + database
                + ", user=" + user
                + ", password=" + redact(password)
                + ", maxPoolSize=" + maxPoolSize
                + ", maxWaitQueueSize=" + maxWaitQueueSize
                + ", eventLoopSize=" + eventLoopSize
                + ", connectionTimeoutMs=" + connectionTimeoutMs
                + ", idleTimeoutMs=" + idleTimeoutMs
                + ", maxLifetimeMs=" + maxLifetimeMs
                + ", poolCleanerPeriodMs=" + poolCleanerPeriodMs
                + ", cachePreparedStatements=" + cachePreparedStatements
                + ", preparedStatementCacheMaxSize=" + preparedStatementCacheMaxSize
                + ", reconnectAttempts=" + reconnectAttempts
                + ", reconnectIntervalMs=" + reconnectIntervalMs
                + ", properties=" + (properties == null || properties.isEmpty() ? "{}" : "<redacted>")
                + ", sslMode=" + sslMode
                + ", trustAll=" + trustAll
                + ", trustStorePath=" + trustStorePath
                + ", trustStorePassword=" + redact(trustStorePassword)
                + ", trustStoreType=" + trustStoreType
                + ", keyPath=" + keyPath
                + ", certPath=" + certPath
                + "]";
    }

    private static String redact(String secret) {
        return secret != null ? "<redacted>" : "null";
    }

    /**
     * Builder shell declared so the generated builder does not render secrets: Lombok merges this
     * class with the generated one and keeps this {@code toString()} instead of generating its own.
     */
    public static class DbPoolConfigBuilder {

        @Override
        public String toString() {
            return "DbPoolConfig.DbPoolConfigBuilder[host=" + host
                    + ", port=" + port
                    + ", database=" + database
                    + ", user=" + user
                    + ", password=" + redact(password)
                    + ", properties=<redacted>"
                    + ", trustStorePath=" + trustStorePath
                    + ", trustStorePassword=" + redact(trustStorePassword)
                    + "]";
        }
    }

    // --- Validation ---

    /**
     * Validates the configuration and returns a list of warning messages for potential
     * misconfigurations. The pool can still be created if warnings are present; this method is
     * intended for logging at startup.
     *
     * @return a list of warning strings, empty if no issues were found
     */
    public List<String> validate() {
        var warnings = new ArrayList<String>();
        if (host == null || host.isBlank()) {
            warnings.add("db.host is not configured");
        }
        if (maxPoolSize <= 0) {
            warnings.add("db.maxPoolSize must be positive, got: " + maxPoolSize);
        }
        if (trustStorePath != null && "DISABLE".equalsIgnoreCase(sslMode)) {
            warnings.add("db.trustStorePath is set but sslMode is DISABLE — trust store will be ignored");
        }
        return warnings;
    }
}
