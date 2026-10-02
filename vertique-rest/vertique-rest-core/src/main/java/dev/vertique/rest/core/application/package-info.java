// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Named REST application declarations (Beta).
 *
 * <p>This is the application-declaration package of {@code vertique-rest-core}. {@link
 * RestApplication} is the sole public type: an annotation processed at compile time by the JAX-RS
 * annotation processor (and retained at runtime), applied to an interface, that declares one named
 * REST application — its name, its mount path, its membership (an explicit resource list or
 * startup discovery), and its OpenAPI contract location. The annotated interface carries no
 * behavior and is never instantiated; {@code vertique-codegen-jaxrs} recognizes the annotation at
 * compile time, validates every declaration, and emits one native registration for it, and
 * {@code vertique-rest-jaxrs} composes those registrations into mounted applications at runtime.
 *
 * <p>This annotation is Beta and outside the Stable promise of {@code vertique-rest-core}: it may
 * change in a later release, and only with a migration note.
 */
package dev.vertique.rest.core.application;
