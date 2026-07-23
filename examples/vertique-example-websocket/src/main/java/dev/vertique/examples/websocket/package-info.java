// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Example application demonstrating WebSocket endpoints with JWT authentication and room-based
 * broadcast. There is no hand-written {@code MainVerticle}: the entry point is
 * {@link dev.vertique.examples.websocket.AppComponent}, the root Dagger {@code @Component} annotated
 * {@link dev.vertique.application.VertiqueApp} and extending
 * {@link dev.vertique.application.VertiqueApplicationComponent}. The
 * {@code vertique-codegen-application} annotation processor generates
 * {@code AppComponentVertiqueComponentFactory} (and its {@code META-INF/services} registration) for
 * that component; at runtime the launcher's bootstrap verticle discovers that factory and the
 * host-neutral lifecycle runner ({@code VertiqueApplicationBootstrap}) builds the component from the
 * pre-resolved {@code config()} tree, runs the framework {@code CONFIGURE}/{@code VALIDATE} steps
 * (Jackson configuration via {@code CoreLifecycleStepsModule}), and deploys the verticles per
 * lifecycle phase ({@link dev.vertique.management.ManagementVerticle} in {@code INFRA}, then
 * {@link dev.vertique.rest.core.router.HttpVerticle} in {@code EDGE}).
 *
 * <p>{@code AppComponent} wires together {@code VertxModule}, {@code AppModule}, {@code RestModule},
 * {@code JwtAuthModule}, {@code WebSocketModule}, {@code SecurityAuthzModule}, {@code ChatModule},
 * and {@code ResourceModule}. {@code AppModule} configures JWT authentication and declares the
 * verticle deployments. {@code ChatModule} wires the {@code ChatEndpoint} WebSocket handler and
 * {@code ChatRoomRegistry}. This module serves as a reference implementation for WebSocket endpoints
 * with JWT-gated upgrade and room-based broadcast fan-out.
 */
package dev.vertique.examples.websocket;
