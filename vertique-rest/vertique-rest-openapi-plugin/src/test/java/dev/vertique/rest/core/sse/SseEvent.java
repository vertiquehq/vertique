// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.sse;

/**
 * Test-only stand-in for {@code dev.vertique.rest.core.sse.SseEvent} (the real class lives in the
 * {@code vertique-rest-core} module, which is deliberately not a dependency of this build-time-only
 * module — see the module javadoc's dependency list).
 *
 * <p>{@link dev.vertique.openapi.SseModelConverter} matches the SSE stream's type argument by
 * comparing its fully-qualified class name against the string literal {@code
 * "dev.vertique.rest.core.sse.SseEvent"}, rather than by type identity, specifically to avoid a
 * compile-time dependency on {@code rest-core}. This class exists so tests can construct a real
 * {@link Class} whose {@link Class#getName()} matches that literal, exercising the converter's
 * positive match path without adding a module dependency. Declared {@code public} so the test in
 * package {@code dev.vertique.openapi} can reference it.
 */
public class SseEvent {

    private SseEvent() {}
}
