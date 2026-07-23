// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.extension.OrderedExtension;

/**
 * Extension point for customizing the Jackson {@link ObjectMapper} used by Vert.x
 * for JSON serialization and deserialization.
 *
 * <p>Implementations are collected via Dagger {@code Set} multibinding and applied
 * by {@link JacksonConfigurer} at application startup in {@link OrderedExtension} order
 * (phase, then priority, then orderKey). Lower {@link #priority()} values execute first.
 *
 * <p>Usage in a Dagger module:
 * <pre>{@code
 * @Provides @IntoSet
 * static ObjectMapperCustomizer javaTimeSupport() {
 *     return mapper -> {
 *         mapper.registerModule(new JavaTimeModule());
 *         mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
 *     };
 * }
 * }</pre>
 */
@FunctionalInterface
public interface ObjectMapperCustomizer extends OrderedExtension {

    /**
     * Customizes the given {@link ObjectMapper}. Called once at application startup.
     *
     * @param mapper the ObjectMapper to customize
     */
    void customize(ObjectMapper mapper);
}
