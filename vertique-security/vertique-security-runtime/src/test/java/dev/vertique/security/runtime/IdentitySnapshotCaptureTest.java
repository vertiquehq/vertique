// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextScopes;
import dev.vertique.core.context.ContextValue;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.IdentitySnapshotFactory;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SystemIdentities;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link IdentitySnapshotCapture} ingress seam
 * ({@link IdentitySnapshotCapture#captureFrom(SecurityContext)}) — PRD-ID-002 §15 A3, slice P1.S5-i
 * "ingress capture seam".
 *
 * <p>Verifies both capture paths: when the {@code captureEnabled} kill-switch is on,
 * {@code captureFrom} builds a snapshot via the injected {@link IdentitySnapshotFactory} and binds
 * it as an {@link IdentitySnapshotContext} whose scope unwinds on close; when the kill-switch is
 * off, {@code captureFrom} short-circuits to a {@link ContextScopes#noop()} scope without ever
 * consulting the factory, so the bind scope is never silently discarded. Also verifies the
 * eligibility gate: a live context whose actor is {@link PrincipalType#ANONYMOUS}, or whose
 * authentication primary method normalizes to {@code AuthMethodKind.NONE}, is never captured even
 * when the kill-switch is on.
 */
class IdentitySnapshotCaptureTest {

    @Test
    @DisplayName("captureFrom binds the factory's snapshot and unbinds it on scope close when enabled")
    void captureFromBindsWhenEnabled() {
        FakeContextHolder holder = new FakeContextHolder();
        IdentitySnapshotContent known = knownContent();
        RecordingFactory factory = new RecordingFactory(known);
        IdentitySnapshotCapture capture = new IdentitySnapshotCapture(holder, factory, true);
        SecurityContext live = SecurityContexts.system(SystemIdentities.scheduledJob("test-job"));

        try (ContextHolder.Scope scope = capture.captureFrom(live)) {
            Optional<IdentitySnapshotContext> bound = holder.current(IdentitySnapshotContext.class);
            assertTrue(bound.isPresent(), "captureFrom must bind an IdentitySnapshotContext when enabled");
            assertSame(
                    known,
                    bound.orElseThrow().content().orElseThrow(),
                    "the bound content must be exactly the one the factory produced");
            assertEquals(1, factory.calls(), "factory.capture must be invoked exactly once");
        }

        assertTrue(
                holder.current(IdentitySnapshotContext.class).isEmpty(),
                "closing the returned scope must unbind the IdentitySnapshotContext");
    }

    @Test
    @DisplayName("captureFrom returns the shared no-op scope and never calls the factory when disabled")
    void captureFromNoopWhenDisabled() {
        FakeContextHolder holder = new FakeContextHolder();
        RecordingFactory factory = new RecordingFactory(knownContent());
        IdentitySnapshotCapture capture = new IdentitySnapshotCapture(holder, factory, false);
        SecurityContext live = SecurityContexts.system(SystemIdentities.scheduledJob("test-job"));

        ContextHolder.Scope scope = capture.captureFrom(live);

        assertSame(ContextScopes.noop(), scope, "a disabled capture must return the shared no-op scope");
        assertTrue(
                holder.current(IdentitySnapshotContext.class).isEmpty(),
                "no IdentitySnapshotContext should be bound when capture is disabled");
        assertEquals(0, factory.calls(), "the factory must never be consulted when capture is disabled");

        scope.close(); // no-op, must not throw
        assertTrue(holder.current(IdentitySnapshotContext.class).isEmpty());
    }

    @Test
    @DisplayName("captureFrom returns the shared no-op scope and never calls the factory for an anonymous actor")
    void captureFromSkipsAnonymousActor() {
        FakeContextHolder holder = new FakeContextHolder();
        RecordingFactory factory = new RecordingFactory(knownContent());
        IdentitySnapshotCapture capture = new IdentitySnapshotCapture(holder, factory, true);
        SecurityContext live = SecurityContexts.unauthenticated(SecurityIdentity.anonymous());

        ContextHolder.Scope scope = capture.captureFrom(live);

        assertSame(ContextScopes.noop(), scope, "an anonymous actor must never be captured — shared no-op scope");
        assertTrue(
                holder.current(IdentitySnapshotContext.class).isEmpty(),
                "no IdentitySnapshotContext should be bound for an anonymous actor");
        assertEquals(0, factory.calls(), "the factory must never be consulted for an anonymous actor");

        scope.close(); // no-op, must not throw
        assertTrue(holder.current(IdentitySnapshotContext.class).isEmpty());
    }

    @Test
    @DisplayName("captureFrom returns the shared no-op scope and never calls the factory for a NONE-auth non-anonymous"
            + " principal")
    void captureFromSkipsNoneAuthWithRealPrincipal() {
        FakeContextHolder holder = new FakeContextHolder();
        RecordingFactory factory = new RecordingFactory(knownContent());
        IdentitySnapshotCapture capture = new IdentitySnapshotCapture(holder, factory, true);
        SecurityContext live = SecurityContexts.unauthenticated(
                SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-42", Map.of())));

        ContextHolder.Scope scope = capture.captureFrom(live);

        assertSame(
                ContextScopes.noop(),
                scope,
                "a NONE-auth non-anonymous principal must never be captured — shared no-op scope");
        assertTrue(
                holder.current(IdentitySnapshotContext.class).isEmpty(),
                "no IdentitySnapshotContext should be bound for a NONE-auth non-anonymous principal");
        assertEquals(0, factory.calls(), "the factory must never be consulted for a NONE-auth non-anonymous principal");

        scope.close(); // no-op, must not throw
        assertTrue(holder.current(IdentitySnapshotContext.class).isEmpty());
    }

    /**
     * Builds a fixed {@link IdentitySnapshotContent} — a sufficient stand-in for a factory's output,
     * since the capture seam binds whatever content the factory returns.
     *
     * @return a fixed content value
     */
    private static IdentitySnapshotContent knownContent() {
        return new IdentitySnapshotContent(
                new PrincipalRef(PrincipalType.USER, "user-42", Map.of()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "jwt",
                Instant.parse("2026-07-01T10:15:30Z"),
                Optional.empty(),
                List.of(),
                "rest:authenticated",
                Instant.parse("2026-07-01T10:15:31Z"));
    }

    /**
     * Stub {@link IdentitySnapshotFactory} that returns fixed content and counts invocations, so a
     * test can assert both the captured value and whether the factory was consulted at all.
     */
    private static final class RecordingFactory implements IdentitySnapshotFactory {

        private final IdentitySnapshotContent content;
        private int calls;

        RecordingFactory(IdentitySnapshotContent content) {
            this.content = content;
        }

        @Override
        public IdentitySnapshotContent capture(SecurityContext live) {
            calls++;
            return content;
        }

        int calls() {
            return calls;
        }
    }

    /**
     * Minimal in-memory {@link ContextHolder} test double that binds into a map and returns a scope
     * restoring the prior binding on close — enough to observe both that a value was bound and that
     * closing the scope unbinds it, without a Vert.x-context-backed holder.
     */
    private static final class FakeContextHolder implements ContextHolder {

        private final Map<Class<?>, Object> bindings = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> current(Class<T> type) {
            return Optional.ofNullable((T) bindings.get(type));
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            Object previous = bindings.put(type, value);
            return () -> {
                if (previous == null) {
                    bindings.remove(type);
                } else {
                    bindings.put(type, previous);
                }
            };
        }
    }
}
