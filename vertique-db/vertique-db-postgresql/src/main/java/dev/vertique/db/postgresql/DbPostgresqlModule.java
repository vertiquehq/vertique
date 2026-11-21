// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.health.HealthCheck;
import dev.vertique.core.health.HealthCheckModule;
import dev.vertique.core.health.Readiness;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.PoolConnectHandler;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Dagger module providing PostgreSQL-specific bindings: connection pool, exception mapper, and
 * connect handler multibinding.
 *
 * <p>Include alongside {@link dev.vertique.db.DbModule} in your Dagger component:
 *
 * <pre>{@code
 * @Component(modules = {DbModule.class, DbPostgresqlModule.class, ...})
 * interface AppComponent { ... }
 * }</pre>
 */
@Module(includes = {HealthCheckModule.class})
public abstract class DbPostgresqlModule {

    /**
     * Declares the optional {@link PoolConnectHandler} binding. Applications provide a concrete
     * implementation via {@code @Provides PoolConnectHandler} in a Dagger module.
     */
    @BindsOptionalOf
    abstract PoolConnectHandler poolConnectHandler();

    /**
     * Provides the singleton {@link PgConnectOptions} for use by components that need
     * a dedicated PostgreSQL connection (e.g., LISTEN/NOTIFY subscribers).
     *
     * <p>Uses the same configuration as the connection pool, ensuring identical
     * SSL, reconnect, and authentication settings.
     *
     * @param config the database pool configuration
     * @return fully configured connect options
     */
    @Provides
    @Singleton
    static PgConnectOptions pgConnectOptions(DbPoolConfig config) {
        return PgConnectOptionsFactory.create(config);
    }

    /**
     * Creates a PostgreSQL connection pool from the {@link DbPoolConfig}. Delegates connection
     * option construction to {@link PgConnectOptionsFactory} to ensure consistent configuration
     * with dedicated subscriber connections.
     *
     * @param vertx          the Vert.x instance
     * @param config         the pool configuration
     * @param connectHandler optional connect handler for connection initialization
     * @return the configured PostgreSQL pool
     */
    @Provides
    @Singleton
    static Pool pgPool(Vertx vertx, DbPoolConfig config, Optional<PoolConnectHandler> connectHandler) {
        PgConnectOptions connectOptions = PgConnectOptionsFactory.create(config);

        PoolOptions poolOptions = new PoolOptions()
                .setMaxSize(config.maxPoolSize())
                .setMaxWaitQueueSize(config.maxWaitQueueSize())
                .setIdleTimeout(config.idleTimeoutMs())
                .setIdleTimeoutUnit(TimeUnit.MILLISECONDS)
                .setConnectionTimeout(config.connectionTimeoutMs())
                .setConnectionTimeoutUnit(TimeUnit.MILLISECONDS)
                .setMaxLifetime(config.maxLifetimeMs())
                .setMaxLifetimeUnit(TimeUnit.MILLISECONDS)
                .setPoolCleanerPeriod(config.poolCleanerPeriodMs())
                .setEventLoopSize(config.eventLoopSize());

        var builder = PgBuilder.pool()
                .with(poolOptions)
                .with(netClientOptions(config))
                .connectingTo(connectOptions)
                .using(vertx);

        connectHandler.ifPresent(handler ->
                builder.withConnectHandler(conn -> handler.handle(conn).onComplete(ar -> conn.close())));

        return builder.build();
    }

    /**
     * Builds the transport options of the pool. Only the TCP connect timeout is set here; SSL
     * settings stay on the connect options.
     *
     * @param config the pool configuration
     * @return transport options carrying {@code connectTimeoutMs}
     */
    static NetClientOptions netClientOptions(DbPoolConfig config) {
        return new NetClientOptions().setConnectTimeout(config.connectTimeoutMs());
    }

    /**
     * Provides the PostgreSQL exception mapper singleton.
     *
     * @return a pre-configured {@link PgDbExceptionMapper}
     */
    @Provides
    @Singleton
    static PgDbExceptionMapper pgDbExceptionMapper() {
        return new PgDbExceptionMapper();
    }

    /**
     * Contributes the database health check as a readiness indicator.
     *
     * @param check the database health check
     * @return the health check instance for readiness multibinding
     */
    @Provides
    @IntoSet
    @Readiness
    static HealthCheck databaseHealthCheck(DatabaseHealthCheck check) {
        return check;
    }

    /**
     * Contributes the step that closes the {@link Pool} this module provides into the
     * {@code Set<ApplicationShutdownStep>} multibinding, so the pool is released when the
     * application stops, after the work that uses it.
     *
     * @param step the pool shutdown step
     * @return the step as an application shutdown contribution
     */
    @Provides
    @Singleton
    @IntoSet
    static ApplicationShutdownStep pgPoolShutdownStep(PgPoolShutdownStep step) {
        return step;
    }
}
