// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Named REST application declarations (Beta).
 *
 * <p>{@link dev.vertique.rest.jaxrs.application.RestApplication} is the sole public type: an
 * annotation processed at compile time by the JAX-RS annotation processor (and retained at
 * runtime), applied to an interface, that declares one named REST application —
 * its name, its mount path, its membership (an explicit resource list or startup discovery), and
 * its OpenAPI contract location. The annotated interface carries no behavior and is never
 * instantiated; {@code vertique-codegen-jaxrs} recognizes the annotation at compile time,
 * validates every declaration, and emits one native registration for it.
 */
package dev.vertique.rest.jaxrs.application;
