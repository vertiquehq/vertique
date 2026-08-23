// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.lifecycle;

import jakarta.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Recursively wraps a JSON-compatible value tree (nested {@code Map}/{@code List}/scalar) into
 * unmodifiable views at every level.
 *
 * <p>A value-observation record's normalized tree must reject mutation anywhere in its nested
 * structure, not only at the outermost map or list, regardless of whether the tree handed to the
 * record's compact constructor was itself already immutable. This package-private helper is the one
 * place both {@link McpToolInputObservation} and {@link McpToolOutputObservation} enforce that.
 */
final class McpValueTrees {

    private McpValueTrees() {}

    /**
     * Returns a deeply unmodifiable copy of {@code source}, preserving key order.
     *
     * @param source the map to copy; must not be {@code null}
     * @return an unmodifiable map whose nested {@code Map}/{@code List} values are unmodifiable too
     */
    static Map<String, Object> deepUnmodifiableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, deepUnmodifiable(value)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns a deeply unmodifiable view of {@code value}, walking a {@code Map}/{@code List} nesting
     * with an explicit work stack rather than native call recursion, so an adversarially deep argument
     * or result tree cannot exhaust the JVM call stack on the caller's thread (mirrors {@code
     * McpEnvelopeJsonCodec#exceedsDecimalScaleBound}'s explicit-stack precedent for the same class of
     * untrusted-depth risk). The walk performs a post-order (children before parent) copy: every
     * nested container is fully built and wrapped before the container that holds it is finished.
     *
     * @param value the value to wrap; a {@code Map} or {@code List} is deep-copied into an
     *     unmodifiable view with every nested value wrapped the same way, and anything else (a
     *     scalar, or {@code null}) is returned unchanged
     * @return the deeply unmodifiable view
     */
    static @Nullable Object deepUnmodifiable(@Nullable Object value) {
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
            return value;
        }
        Object[] rootHolder = new Object[1];
        Deque<StackEntry> stack = new ArrayDeque<>();
        stack.push(new Expand(value, result -> rootHolder[0] = result));
        while (!stack.isEmpty()) {
            StackEntry entry = stack.pop();
            if (entry instanceof Finish finish) {
                finish.sink().accept(finish.wrap());
                continue;
            }
            Expand expand = (Expand) entry;
            expandOnto(stack, expand);
        }
        return rootHolder[0];
    }

    /**
     * Expands one pending value: a scalar is sunk immediately, and a {@code Map}/{@code List} pushes
     * its own deferred {@link Finish} beneath a child {@link Expand} for every entry/element, so the
     * children are popped (and therefore fully processed) before the parent's {@link Finish} runs.
     */
    private static void expandOnto(Deque<StackEntry> stack, Expand expand) {
        Object source = expand.value();
        if (source instanceof Map<?, ?> map) {
            Map<Object, Object> working = new LinkedHashMap<>();
            stack.push(new Finish(() -> Collections.unmodifiableMap(working), expand.sink()));
            List<? extends Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            for (int i = entries.size() - 1; i >= 0; i--) {
                Map.Entry<?, ?> mapEntry = entries.get(i);
                Object key = mapEntry.getKey();
                stack.push(new Expand(mapEntry.getValue(), childValue -> working.put(key, childValue)));
            }
        } else if (source instanceof List<?> list) {
            List<Object> working = new ArrayList<>(Collections.nCopies(list.size(), null));
            stack.push(new Finish(() -> Collections.unmodifiableList(working), expand.sink()));
            for (int i = list.size() - 1; i >= 0; i--) {
                int index = i;
                stack.push(new Expand(list.get(i), childValue -> working.set(index, childValue)));
            }
        } else {
            expand.sink().accept(source);
        }
    }

    /** One entry on the explicit work stack: either a value still to expand, or a container to finish. */
    private sealed interface StackEntry permits Expand, Finish {}

    /** A pending raw value that still needs classifying (scalar, {@code Map}, or {@code List}). */
    private record Expand(@Nullable Object value, Consumer<Object> sink) implements StackEntry {}

    /**
     * A container whose children have all already been scheduled; popped only after every child
     * {@link Expand} pushed alongside it has been popped and processed, so {@code wrap} always sees a
     * fully populated working container.
     */
    private record Finish(Supplier<Object> wrapSupplier, Consumer<Object> sink) implements StackEntry {
        Object wrap() {
            return wrapSupplier.get();
        }
    }
}
