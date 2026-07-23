// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Dagger dependency injection wiring for the REST core module.
 *
 * <p>{@link dev.vertique.rest.core.dagger.RestCoreModule} is the central Dagger {@code @Module}
 * that declares all multibinding sets for the extension points in the REST framework: router
 * mounts, customizers, lifecycle hooks, middlewares, security scheme handlers, response producers,
 * body encoders, request body decoders, and JAX-RS resources. It also provides default
 * singleton bindings for {@link dev.vertique.rest.core.config.HttpConfig},
 * {@link dev.vertique.rest.core.config.CorsConfig}, and
 * {@link dev.vertique.rest.core.config.JaxRsConfig}.
 *
 * <p>{@link dev.vertique.rest.core.dagger.JaxRsResources} is the qualifier annotation used to
 * distinguish the JAX-RS resource multibinding set from other {@code Set<Object>} bindings.
 */
package dev.vertique.rest.core.dagger;
