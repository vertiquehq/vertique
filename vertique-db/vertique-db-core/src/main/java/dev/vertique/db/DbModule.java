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

/**
 * Dagger module providing the {@link DbPoolConfig} binding. Reads the {@code "db"} section from
 * the Vert.x application config.
 *
 * <p>Include this module in your Dagger component alongside a vendor module (e.g., {@code
 * DbPostgresqlModule}) to get a fully configured pool.
 */
@Module
public class DbModule {

    /**
     * Provides the database pool configuration from the {@code "db"} config section.
     *
     * @param config the application config
     * @param parser the injected config parser
     * @return the pool configuration
     */
    @Provides
    @Singleton
    static DbPoolConfig dbPoolConfig(@VertxConfig JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "db"), DbPoolConfig.class);
    }
}
