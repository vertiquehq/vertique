// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Jackson ObjectMapper configuration support for Vert.x.
 *
 * <p>Provides a Dagger-based extension point ({@link dev.vertique.core.json.ObjectMapperCustomizer})
 * for customizing the Jackson {@link com.fasterxml.jackson.databind.ObjectMapper} used by Vert.x
 * for all JSON serialization and deserialization.
 *
 * @see dev.vertique.core.json.JsonModule
 * @see dev.vertique.core.json.JacksonConfigurer
 */
package dev.vertique.core.json;
