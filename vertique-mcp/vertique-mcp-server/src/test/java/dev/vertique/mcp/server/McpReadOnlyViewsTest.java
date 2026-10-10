// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Read-only views over a JSON-compatible tree: no write path at any level, no copy, no eager walk. */
class McpReadOnlyViewsTest {

    private static Map<String, Object> sampleTree() {
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        nestedMap.put("name", "Ada");
        nestedMap.put("score", 7);
        List<Object> nestedList = new ArrayList<>(List.of("vip", "returning"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("customer", nestedMap);
        root.put("tags", nestedList);
        root.put("flag", true);
        root.put("none", null);
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapView(Object value) {
        return (Map<String, Object>) McpReadOnlyViews.of(value);
    }

    @Test
    @DisplayName("a map view refuses every write at the root and at a nested map")
    void shouldRefuseEveryMapWriteAtEveryLevel() {
        // Given: a tree with a nested map, wrapped in a view.
        Map<String, Object> view = mapView(sampleTree());
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) view.get("customer");

        // When / Then: put, remove, clear, putAll and entry writes throw on both levels.
        for (Map<String, Object> level : List.of(view, nested)) {
            assertThatThrownBy(() -> level.put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> level.putAll(Map.of("x", "y"))).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> level.remove(level.keySet().iterator().next()))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(level::clear).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> level.entrySet().iterator().next().setValue("x"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> level.keySet().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> level.values().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> level.merge("k", "v", (a, b) -> b))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    @DisplayName("a list view refuses every write at a nested list")
    void shouldRefuseEveryListWriteAtANestedLevel() {
        // Given: a nested list reached through a map view.
        Map<String, Object> view = mapView(sampleTree());
        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) view.get("tags");

        // When / Then: add, set, remove, clear and addAll throw.
        assertThatThrownBy(() -> tags.add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tags.add(0, "x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tags.set(0, "x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tags.remove(0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tags.remove("vip")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(tags::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tags.addAll(List.of("x"))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> tags.sort(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a list at the root is wrapped read-only too, with maps inside it")
    void shouldWrapARootListAndTheMapsInsideIt() {
        // Given: a root list holding a map.
        Map<String, Object> element = new HashMap<>(Map.of("a", "b"));
        List<Object> root = new ArrayList<>(List.of(element, "tail"));

        // When: it is wrapped.
        @SuppressWarnings("unchecked")
        List<Object> view = (List<Object>) McpReadOnlyViews.of(root);

        // Then: the list and the map inside it refuse writes.
        assertThatThrownBy(() -> view.add("x")).isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) view.get(0);
        assertThatThrownBy(() -> inner.put("c", "d")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(view.get(1)).isEqualTo("tail");
    }

    @Test
    @DisplayName("scalars and null pass through unchanged")
    void shouldPassScalarsAndNullThrough() {
        Map<String, Object> view = mapView(sampleTree());

        assertThat(McpReadOnlyViews.of(null)).isNull();
        assertThat(McpReadOnlyViews.of("text")).isEqualTo("text");
        assertThat(McpReadOnlyViews.of(42)).isEqualTo(42);
        assertThat(McpReadOnlyViews.of(Boolean.TRUE)).isEqualTo(Boolean.TRUE);
        assertThat(view.get("flag")).isEqualTo(true);
        assertThat(view.containsKey("none")).isTrue();
        assertThat(view.get("none")).isNull();
        assertThat(view.get("absent")).isNull();
    }

    @Test
    @DisplayName("iteration order and lookups match the delegate")
    void shouldMatchTheDelegateInOrderAndLookup() {
        // Given: a delegate with a deliberate, non-sorted key order.
        Map<String, Object> delegate = new LinkedHashMap<>();
        delegate.put("zulu", 1);
        delegate.put("alpha", 2);
        delegate.put("mike", List.of(3, 4));

        // When: viewed.
        Map<String, Object> view = mapView(delegate);

        // Then: keys iterate in the delegate's order and every lookup agrees.
        assertThat(view.keySet()).containsExactly("zulu", "alpha", "mike");
        assertThat(view.values()).containsExactly(1, 2, List.of(3, 4));
        assertThat(view.size()).isEqualTo(3);
        assertThat(view.get("alpha")).isEqualTo(2);
        assertThat(view.get("mike")).isEqualTo(List.of(3, 4));
        assertThat(view).isEqualTo(delegate);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) McpReadOnlyViews.of(new ArrayList<>(List.of("a", "b", "c")));
        assertThat(list).containsExactly("a", "b", "c");
        assertThat(list.get(2)).isEqualTo("c");
    }

    @Test
    @DisplayName("a view reflects later changes of its delegate: it is a view, not a copy")
    void shouldReflectLaterChangesOfTheDelegate() {
        // Given: a view over a mutable tree.
        Map<String, Object> tree = sampleTree();
        Map<String, Object> view = mapView(tree);
        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) view.get("tags");

        // When: the delegate (by its owner) changes.
        tree.put("added", "later");
        @SuppressWarnings("unchecked")
        List<Object> delegateTags = (List<Object>) tree.get("tags");
        delegateTags.add("late-tag");

        // Then: the view and the already-obtained nested view show it.
        assertThat(view.get("added")).isEqualTo("later");
        assertThat(view.containsKey("added")).isTrue();
        assertThat(tags).containsExactly("vip", "returning", "late-tag");
    }

    @Test
    @DisplayName("nothing is walked when a view is built, only when it is read")
    void shouldWalkNothingWhenAViewIsBuilt() {
        // Given: a delegate map and list that throw as soon as they are iterated or indexed.
        Map<String, Object> exploding = new AbstractMap<>() {
            @Override
            public Set<Entry<String, Object>> entrySet() {
                throw new IllegalStateException("walked");
            }
        };
        List<Object> explodingList = new ArrayList<>() {
            @Override
            public Object get(int index) {
                throw new IllegalStateException("walked");
            }

            @Override
            public java.util.Iterator<Object> iterator() {
                throw new IllegalStateException("walked");
            }
        };
        Map<String, Object> holder = new LinkedHashMap<>();
        holder.put("bomb", exploding);
        holder.put("bombList", explodingList);

        // When / Then: building the views, even over a parent that holds them, never touches them.
        assertThatCode(() -> McpReadOnlyViews.of(exploding)).doesNotThrowAnyException();
        assertThatCode(() -> McpReadOnlyViews.of(explodingList)).doesNotThrowAnyException();
        assertThatCode(() -> McpReadOnlyViews.of(holder)).doesNotThrowAnyException();

        // And: reading them does surface the delegate's failure.
        Map<String, Object> view = mapView(holder);
        assertThatCode(() -> view.get("bomb")).doesNotThrowAnyException();
        assertThatThrownBy(() -> mapView(exploding).forEach((k, v) -> {})).isInstanceOf(IllegalStateException.class);
        @SuppressWarnings("unchecked")
        List<Object> listView = (List<Object>) McpReadOnlyViews.of(explodingList);
        assertThatThrownBy(() -> listView.get(0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a 200,000-level nested map can be wrapped and walked without error")
    void shouldWrapAnAdversariallyDeepMapWithoutError() {
        // Given: a map nested 200,000 levels deep, built iteratively.
        int levels = 200_000;
        Map<String, Object> root = new HashMap<>();
        Map<String, Object> cursor = root;
        for (int level = 1; level < levels; level++) {
            Map<String, Object> next = new HashMap<>();
            cursor.put("k", next);
            cursor = next;
        }
        cursor.put("k", "leaf");

        // When: it is wrapped, then walked with a loop (no recursion).
        Map<String, Object> view = mapView(root);
        int walked = 1;
        Object node = view;
        while (true) {
            @SuppressWarnings("unchecked")
            Object child = ((Map<String, Object>) node).get("k");
            if (!(child instanceof Map<?, ?>)) {
                node = child;
                break;
            }
            node = child;
            walked++;
        }

        // Then: every level was reached, and the leaf is the scalar at the bottom.
        assertThat(walked).isEqualTo(levels);
        assertThat(node).isEqualTo("leaf");
    }
}
