// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * JUnit 5 test-support for booting a Vertique application in one line.
 *
 * <p>The single public type is {@link dev.vertique.application.test.VertiqueAppExtension}, a JUnit 5
 * extension that an integration test registers as a {@code @RegisterExtension static final} field.
 * It owns the awaited startup ({@link
 * dev.vertique.application.VertiqueApplicationBootstrap#start}) in {@code beforeAll} and the awaited
 * teardown ({@link dev.vertique.application.VertiqueApplicationHandle#shutdown}, then an owned
 * {@code Vertx} close) in {@code afterAll} — so no test hand-writes the bootstrap dance or the
 * easy-to-forget awaited teardown. After {@code beforeAll}, the test reads the started application's
 * {@link dev.vertique.application.VertiqueApplicationHandle handle}, its built component, its
 * configuration, the {@code Vertx} instance, and (when an HTTP server started) its bound port.
 *
 * <p>This module depends only on {@code vertique-application} (the host-neutral lifecycle runner),
 * {@code vertique-core} (the neutral {@link dev.vertique.core.VertiqueRuntime} /
 * {@link dev.vertique.core.VertiqueComponentFactory} seam), {@code vertx-core}, and the JUnit 5 API.
 * It carries no host (Spring, Quarkus) coupling and no production runtime concern.
 */
package dev.vertique.application.test;
