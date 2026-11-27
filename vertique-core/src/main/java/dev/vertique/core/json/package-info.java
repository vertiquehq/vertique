// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * JSON profiles and the process JSON codec, without a JSON library type in their signatures.
 *
 * <p>A {@link dev.vertique.core.json.JsonProfileCodec} is one named JSON configuration seen through
 * encode, decode and convert operations; a {@link dev.vertique.core.json.JsonProfileRegistry} resolves a
 * {@link dev.vertique.core.json.JsonProfileId} to its profile. Profiles are the only supported way to
 * configure JSON for a framework boundary. The Jackson 2 implementation and the registry live in
 * {@code dev.vertique:vertique-json}.
 *
 * <p>{@code vertique-json} also registers the framework's codec as the Vert.x process JSON codec through
 * the {@code io.vertx.core.spi.JsonFactory} service loader; {@link dev.vertique.core.json.VertiqueJson} is
 * the accessor and install surface for the profile that codec runs.
 *
 * @see dev.vertique.core.json.VertiqueJson
 * @see dev.vertique.core.json.JsonProfileRegistry
 */
package dev.vertique.core.json;
