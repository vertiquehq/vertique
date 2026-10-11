// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import jakarta.annotation.Nullable;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only views over a JSON-compatible value tree, with no copy.
 *
 * <p>A {@code Map} or {@code List} is wrapped on demand: reading a nested container hands back a
 * wrapper over it, so a listener can read the whole tree but change no level of it. Nothing is
 * walked when a view is built, so an adversarially deep tree costs nothing until a reader walks it.
 * Scalars pass through unchanged.
 *
 * <p>The view is only as stable as the tree under it. The framework builds the tree once and does
 * not change it after it is bound to a request.
 */
final class McpReadOnlyViews {

    private McpReadOnlyViews() {}

    /**
     * Returns a read-only view of {@code value}.
     *
     * @param value a {@code Map}, a {@code List} or a scalar, or {@code null}
     * @return a read-only view when {@code value} is a container, otherwise {@code value} itself
     */
    @Nullable
    static Object of(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            return new ReadOnlyMap(map);
        }
        if (value instanceof List<?> list) {
            return new ReadOnlyList(list);
        }
        return value;
    }

    private static final class ReadOnlyMap extends AbstractMap<String, Object> {
        private final Map<?, ?> delegate;

        private ReadOnlyMap(Map<?, ?> delegate) {
            this.delegate = delegate;
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean containsKey(Object key) {
            return delegate.containsKey(key);
        }

        @Override
        public Object get(Object key) {
            return of(delegate.get(key));
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public int size() {
                    return delegate.size();
                }

                @Override
                public Iterator<Entry<String, Object>> iterator() {
                    Iterator<? extends Entry<?, ?>> source = delegate.entrySet().iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return source.hasNext();
                        }

                        @Override
                        public Entry<String, Object> next() {
                            Entry<?, ?> entry = source.next();
                            return new SimpleImmutableEntry<>(String.valueOf(entry.getKey()), of(entry.getValue()));
                        }
                    };
                }
            };
        }
    }

    private static final class ReadOnlyList extends AbstractList<Object> {
        private final List<?> delegate;

        private ReadOnlyList(List<?> delegate) {
            this.delegate = delegate;
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public Object get(int index) {
            return of(delegate.get(index));
        }
    }
}
