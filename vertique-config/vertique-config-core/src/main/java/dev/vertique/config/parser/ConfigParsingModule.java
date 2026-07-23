// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dev.vertique.core.config.ConfigMapper;
import dev.vertique.core.config.ConfigParser;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Dagger module wiring the framework's single {@link ConfigParser} binding. Applications include
 * this module once at the application-component layer so every boundary provider can inject the
 * canonical config parser.
 *
 * <p>The parser is backed either by the lenient default config mapper or, when the application binds
 * an {@code @ConfigMapper ObjectMapper} override, by that override finalized for config parsing
 * (mandatory modules + lenient policy re-layered in place). The override is optional via
 * {@link BindsOptionalOf}, so an application that does not customize config parsing needs no extra
 * binding.
 */
@Module
public abstract class ConfigParsingModule {

    /**
     * Declares the optional application-supplied config-mapper override. When the application binds
     * an {@code @ConfigMapper ObjectMapper}, it is finalized for config and used to back the parser;
     * otherwise the lenient default is used.
     *
     * @return the optional override binding declaration
     */
    @BindsOptionalOf
    @ConfigMapper
    abstract ObjectMapper configMapperOverride();

    /**
     * Provides the framework's single {@link ConfigParser}, backed by the application's
     * {@code @ConfigMapper} override (finalized for config) when present, or the lenient default
     * config mapper otherwise.
     *
     * @param override the optional application-supplied config-mapper override
     * @return the singleton config parser
     */
    @Provides
    @Singleton
    static ConfigParser configParser(@ConfigMapper Optional<ObjectMapper> override) {
        ObjectMapper mapper =
                override.map(DefaultConfigMapper::finalizeForConfig).orElseGet(DefaultConfigMapper::lenient);
        return new DefaultConfigParser(mapper);
    }
}
