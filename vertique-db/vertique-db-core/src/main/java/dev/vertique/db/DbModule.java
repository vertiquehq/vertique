// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import dagger.Module;
import dagger.Provides;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Dagger module providing the {@link DbPoolConfig} and {@link DbSchemas} bindings. Reads the
 * {@code "db"} section from the Vert.x application config.
 *
 * <p>Include this module in your Dagger component alongside a vendor module (e.g., {@code
 * DbPostgresqlModule}) to get a fully configured pool.
 *
 * <p>Each warning returned by {@link DbPoolConfig#validate()} is logged at WARN when the binding is
 * created; a warning never fails startup.
 *
 * <p>{@link DbSchemas} is bound from {@code db.schemas}, a JSON object from placement name to
 * schema name. It is lazy and opens no connection. An application that supplies its own {@link
 * DbSchemas} binding must not include this module.
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

    /**
     * Provides the schema placement from the {@code "db.schemas"} config setting. An absent {@code
     * db} section and an absent or JSON-null {@code schemas} setting yield {@link
     * DbSchemas#empty()}. Values are kept verbatim: the binding applies no vendor identifier rules.
     *
     * @param config the application config
     * @return the placement metadata
     * @throws ConfigurationException if {@code db} or {@code db.schemas} is not an object, or an
     *     entry has a blank key or a value that is not a non-blank string; the message names the
     *     setting and never includes a configured value
     */
    @Provides
    @Singleton
    static DbSchemas dbSchemas(@VertxConfig JsonObject config) {
        if (!config.containsKey("db")) {
            return DbSchemas.empty();
        }
        if (!(config.getValue("db") instanceof JsonObject db)) {
            throw new ConfigurationException("Config path 'db' must be a JSON object");
        }
        if (db.getValue("schemas") == null) {
            return DbSchemas.empty();
        }
        if (!(db.getValue("schemas") instanceof JsonObject schemas)) {
            throw new ConfigurationException("Config path 'db.schemas' must be a JSON object");
        }
        Map<String, String> mappings = new LinkedHashMap<>();
        for (String name : schemas.fieldNames()) {
            if (name.isBlank()) {
                throw new ConfigurationException("Config path 'db.schemas' must not contain a blank key");
            }
            if (!(schemas.getValue(name) instanceof String schema) || schema.isBlank()) {
                throw new ConfigurationException("Config path 'db.schemas." + name + "' must be a non-blank string");
            }
            mappings.put(name, schema);
        }
        return new DbSchemas(mappings);
    }
}
