// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dev.vertique.db.DbPoolConfig;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.TrustOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.SslMode;
import java.util.Locale;

/**
 * INTERNAL — consumed only by {@link DbPostgresqlModule}; not an application contract and free to
 * change without notice. Applications reach its output through the {@link PgConnectOptions} binding.
 *
 * <p>Factory for creating {@link PgConnectOptions} from {@link DbPoolConfig}.
 *
 * <p>Centralizes PostgreSQL connection option construction so that the connection pool and any
 * dedicated subscriber connection (e.g., LISTEN/NOTIFY) built from the {@link PgConnectOptions}
 * binding that {@link DbPostgresqlModule} provides use identical configuration: host, port, user,
 * password, database, SSL mode, trust/key material, reconnect settings, prepared-statement cache,
 * and vendor properties.
 */
public final class PgConnectOptionsFactory {

    private PgConnectOptionsFactory() {}

    /**
     * Creates {@link PgConnectOptions} from the given pool config, applying all connection
     * settings including SSL/TLS when configured.
     *
     * @param config the database pool configuration
     * @return fully configured connect options
     */
    public static PgConnectOptions create(DbPoolConfig config) {
        PgConnectOptions connectOptions = new PgConnectOptions()
                .setHost(config.host())
                .setPort(config.port())
                .setDatabase(config.database())
                .setUser(config.user())
                .setPassword(config.password())
                .setCachePreparedStatements(config.cachePreparedStatements())
                .setPreparedStatementCacheMaxSize(config.preparedStatementCacheMaxSize())
                .setReconnectAttempts(config.reconnectAttempts())
                .setReconnectInterval(config.reconnectIntervalMs());
        if (config.properties() != null && !config.properties().isEmpty()) {
            connectOptions.setProperties(config.properties());
        }

        applySsl(connectOptions, config);
        return connectOptions;
    }

    // --- SSL ---

    /**
     * Applies SSL/TLS configuration to the connect options when {@code sslMode} is not
     * {@code DISABLE}.
     *
     * @param connectOptions the connect options to configure
     * @param config the pool configuration providing SSL settings
     */
    private static void applySsl(PgConnectOptions connectOptions, DbPoolConfig config) {
        String sslMode = config.sslMode();
        if (sslMode == null || "DISABLE".equalsIgnoreCase(sslMode)) {
            return;
        }
        connectOptions.setSslMode(SslMode.valueOf(sslMode.toUpperCase(Locale.ROOT)));
        ClientSSLOptions sslOptions = new ClientSSLOptions();
        if (config.trustAll()) {
            sslOptions.setTrustAll(true);
        }
        if (config.trustStorePath() != null) {
            sslOptions.setTrustOptions(resolveTrustOptions(config));
        }
        if (config.keyPath() != null && config.certPath() != null) {
            sslOptions.setKeyCertOptions(
                    new PemKeyCertOptions().setKeyPath(config.keyPath()).setCertPath(config.certPath()));
        }
        connectOptions.setSslOptions(sslOptions);
    }

    /**
     * Resolves the appropriate {@link TrustOptions} from the pool config. The trust store type is
     * auto-detected from the file extension of {@link DbPoolConfig#trustStorePath()} when
     * {@link DbPoolConfig#trustStoreType()} is not set.
     *
     * @param config the pool configuration
     * @return the resolved trust options
     */
    private static TrustOptions resolveTrustOptions(DbPoolConfig config) {
        String type = config.trustStoreType();
        if (type == null) {
            String path = config.trustStorePath();
            if (path.endsWith(".pem") || path.endsWith(".crt")) {
                type = "PEM";
            } else if (path.endsWith(".jks")) {
                type = "JKS";
            } else if (path.endsWith(".p12") || path.endsWith(".pfx")) {
                type = "PKCS12";
            } else {
                type = "PEM";
            }
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "JKS" -> new JksOptions().setPath(config.trustStorePath()).setPassword(config.trustStorePassword());
            case "PKCS12" -> new PfxOptions().setPath(config.trustStorePath()).setPassword(config.trustStorePassword());
            default -> new PemTrustOptions().addCertPath(config.trustStorePath());
        };
    }
}
