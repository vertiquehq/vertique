// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Typed, validated configuration model for the {@code services} dispatch section.
 *
 * <p>The external config keeps an operator-friendly keyed-object shape
 * ({@code services.contracts.{namespace}.{name}}, operations under {@code operations.{operation}});
 * these records
 * are the typed, validated result assembled at the {@code DispatchModule} provider boundary, with
 * each object key injected into a typed identity field. Module internals depend on
 * {@link dev.vertique.services.config.ServicesConfig} (or its index), never on the raw
 * {@link io.vertx.core.json.JsonObject}.
 */
package dev.vertique.services.config;
