// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * JSON mapper profiles and the process JSON codec.
 *
 * <p>A {@link dev.vertique.core.json.JsonMapperProfile} names a code-owned Jackson
 * {@link com.fasterxml.jackson.databind.ObjectMapper} configuration; a
 * {@link dev.vertique.core.json.JsonMapperProfileRegistry} resolves a
 * {@link dev.vertique.core.json.JsonProfileId} to that mapper. Profiles are the only supported way
 * to configure Jackson for a framework JSON boundary.
 *
 * <p>{@link dev.vertique.core.json.VertiqueJsonFactory} registers the framework's codec as the
 * Vert.x process JSON codec through the {@code io.vertx.core.spi.JsonFactory} service loader, and
 * {@link dev.vertique.core.json.VertiqueJson} is the accessor and install surface for the mapper
 * that codec runs on.
 *
 * @see dev.vertique.core.json.VertiqueJson
 * @see dev.vertique.core.json.JsonMapperProfileRegistry
 */
package dev.vertique.core.json;
