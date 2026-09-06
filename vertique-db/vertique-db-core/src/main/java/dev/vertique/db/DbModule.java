// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Dagger module providing the {@link DbPoolConfig} binding. Reads the {@code "db"} section from
 * the Vert.x application config.
 *
 * <p>Include this module in your Dagger component alongside a vendor module (e.g., {@code
 * DbPostgresqlModule}) to get a fully configured pool.
 *
 * <p>Each warning returned by {@link DbPoolConfig#validate()} is logged at WARN when the binding is
 * created; a warning never fails startup.
 */
@Slf4j
@Module
public abstract class DbModule {

    /**
     * Provides the database pool configuration from the {@code "db"} config section.
     *
     * @param config the application config
     * @param parser the injected config parser
     * @return the pool configuration, after its validation warnings have been logged
     */
    @Provides
    @Singleton
    static DbPoolConfig dbPoolConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        DbPoolConfig poolConfig = parser.parse(JsonConfigPaths.navigateObject(config, "db"), DbPoolConfig.class);
        for (String warning : poolConfig.validate()) {
            log.warn("Database pool configuration: {}", warning);
        }
        return poolConfig;
    }
}
