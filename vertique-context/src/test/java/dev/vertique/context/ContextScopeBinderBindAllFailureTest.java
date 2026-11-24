// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ContextScopeBinder#bindAll} when an individual {@code bind} fails part-way through
 * the loop, using a recording holder so no Vert.x context is needed.
 */
class ContextScopeBinderBindAllFailureTest {

    record First(String id) implements ContextValue {}

    record Second(String id) implements ContextValue {}

    record Third(String id) implements ContextValue {}

    /** Holder that records bind and close order and fails the bind of one chosen type. */
    private static final class RecordingHolder implements ContextHolder {
        final List<String> events = new ArrayList<>();
        private final Class<?> failingType;
        private final RuntimeException failure;

        RecordingHolder(Class<?> failingType, RuntimeException failure) {
            this.failingType = failingType;
            this.failure = failure;
        }

        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            if (type == failingType) {
                events.add("bind-failed:" + type.getSimpleName());
                throw failure;
            }
            events.add("bind:" + type.getSimpleName());
            return () -> events.add("close:" + type.getSimpleName());
        }
    }

    @Test
    @DisplayName("a bind failure mid-loop unwinds the already-bound values in reverse order and propagates the failure")
    void midLoopBindFailureUnwindsEarlierBindsAndPropagates() {
        IllegalStateException failure = new IllegalStateException("no active Vert.x context");
        RecordingHolder holder = new RecordingHolder(Third.class, failure);
        ContextScopeBinder binder = new ContextScopeBinder(holder);
        Map<Class<? extends ContextValue>, ContextValue> values = new LinkedHashMap<>();
        values.put(First.class, new First("1"));
        values.put(Second.class, new Second("2"));
        values.put(Third.class, new Third("3"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> binder.bindAll(values));

        assertSame(failure, thrown, "the original bind failure must propagate unchanged");
        assertEquals(
                List.of("bind:First", "bind:Second", "bind-failed:Third", "close:Second", "close:First"),
                holder.events,
                "the two installed binds must be closed in reverse order after the third bind fails");
    }

    @Test
    @DisplayName("a close failure during the unwind is attached as suppressed and the bind failure still propagates")
    void closeFailureDuringUnwindIsSuppressed() {
        IllegalStateException failure = new IllegalStateException("bind failed");
        RuntimeException closeFailure = new RuntimeException("close failed");
        List<String> closed = new ArrayList<>();
        ContextHolder holder = new ContextHolder() {
            @Override
            public <T> Optional<T> current(Class<T> type) {
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                if (type == Second.class) {
                    throw failure;
                }
                return () -> {
                    closed.add(type.getSimpleName());
                    throw closeFailure;
                };
            }
        };
        ContextScopeBinder binder = new ContextScopeBinder(holder);
        Map<Class<? extends ContextValue>, ContextValue> values = new LinkedHashMap<>();
        values.put(First.class, new First("1"));
        values.put(Second.class, new Second("2"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> binder.bindAll(values));

        assertSame(failure, thrown);
        assertEquals(List.of("First"), closed);
        assertTrue(List.of(thrown.getSuppressed()).contains(closeFailure));
    }
}
