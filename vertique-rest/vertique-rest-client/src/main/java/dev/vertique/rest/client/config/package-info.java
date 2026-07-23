// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Typed, validated configuration model for the {@code restClient} section.
 *
 * <p>The external config keeps an operator-friendly section-root-keyed shape — {@code
 * restClient.{name}} — where client names are the keys at the section root (there is no wrapper
 * field). These records are the typed, validated result assembled at the {@code RestClientModule}
 * provider boundary, with each object key injected into the {@link
 * dev.vertique.rest.client.config.RestClientConfig#name() name} identity field. Module internals —
 * {@code RestClientFactory} and {@code RestClientBuilder} — depend on {@link
 * dev.vertique.rest.client.config.RestClientConfig} (or its {@code name -> RestClientConfig} index),
 * never on the raw {@link io.vertx.core.json.JsonObject}.
 *
 * <p>The {@code webClient} component is an intentionally-open Vert.x {@code WebClientOptions}
 * pass-through bag (config rule R9) whose 7 framework duration keys are normalized to Vert.x-native
 * names at parse time.
 */
package dev.vertique.rest.client.config;
