// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.mcp.interceptor.McpRequestContext;
import dev.vertique.mcp.interceptor.McpToolInvocationContext;
import dev.vertique.mcp.lifecycle.McpMethod;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpToolInputObservation;
import dev.vertique.mcp.lifecycle.McpToolValueObservation;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Framework wiring for {@link McpValueObservationLeastPrivilegeTest} (T018 TP-002): fixture data,
 * fixture types, and the bounded reflective reachability probes the decisive assertions are built
 * from. Nothing in this class is itself a decisive assertion.
 */
final class McpValueObservationLeastPrivilegeTestFixture {

    /** Bounds the reflective object-graph walk so it cannot run away over an unrelated dependency. */
    private static final int MAX_DEPTH = 16;

    private McpValueObservationLeastPrivilegeTestFixture() {}

    /**
     * A materialized carrier standing in for a real request-scope holder: its {@code
     * normalizedArguments} is the bounded tree a value observation may legitimately receive, while
     * {@code rawHeaders}, {@code credential}, and {@code rawBody} are the raw transport-level values
     * that same scope also holds but a value observation must never expose a path back to.
     */
    record SourceCarrier(
            Map<String, String> rawHeaders,
            String credential,
            Buffer rawBody,
            Map<String, Object> normalizedArguments) {}

    static SourceCarrier rawSourceCarrier() {
        Map<String, String> rawHeaders = new LinkedHashMap<>();
        rawHeaders.put("Authorization", "Bearer raw-secret-token-should-never-leak");
        rawHeaders.put("Cookie", "session=raw-cookie-should-never-leak");
        String credential = "raw-credential-should-never-leak";
        Buffer rawBody = Buffer.buffer("{\"raw\":true,\"city\":\"  Helsinki  \"}");

        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("city", "Helsinki");
        List<String> tags = new ArrayList<>(List.of("vip", "returning"));
        Map<String, Object> normalizedArguments = new LinkedHashMap<>();
        normalizedArguments.put("customer", customer);
        normalizedArguments.put("tags", tags);

        return new SourceCarrier(Map.copyOf(rawHeaders), credential, rawBody, normalizedArguments);
    }

    static McpToolInvocationContext toolContext() {
        McpToolDescriptor descriptor = new McpToolDescriptor(
                "value.observation.fixture.tool",
                null,
                "T018 TP-002 fixture tool.",
                new McpToolAnnotations(true, false, true, false),
                "{\"type\":\"object\"}",
                null,
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        McpRequestContext requestContext = new McpRequestContext(
                McpMethod.TOOLS_CALL, SecurityContexts.unauthenticated(SecurityIdentity.anonymous()), null, null);
        return new McpToolInvocationContext(requestContext, descriptor);
    }

    static McpCompletionCoordinator coordinatorFor(Context context, McpRequestObservation session) {
        McpRequestLifecycleObserver observer = startedAt -> session;
        return new McpCompletionCoordinator(context, Set.of(observer), Set.of(), Instant.now());
    }

    /**
     * Reports whether {@code observation}'s reachable object graph excludes every raw source in
     * {@code carrier} — identity, never equality, so an unrelated value that merely equals a raw
     * source does not produce a false positive.
     */
    static boolean excludesRawSources(McpToolInputObservation observation, SourceCarrier carrier) {
        Set<Object> targets = Collections.newSetFromMap(new IdentityHashMap<>());
        targets.add(carrier.rawHeaders());
        targets.add(carrier.credential());
        targets.add(carrier.rawBody());
        return !isReachable(observation, targets, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    /**
     * Counts how many of {@code coordinator}'s own declared fields — and, one level deeper, the
     * elements of a {@code Map}/{@code Iterable} field value — hold a reference identical to one of
     * {@code targets}. This is the "framework-owned reference still reachable" instrument TP-002
     * requires: it inspects the coordinator's actual field values through reflection rather than
     * trusting that nothing was stored.
     */
    static int countFrameworkReferences(McpCompletionCoordinator coordinator, Object... targets) {
        Set<Object> targetSet = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(targetSet, targets);
        int matches = 0;
        for (Field field : allInstanceFields(McpCompletionCoordinator.class)) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(coordinator);
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException(impossible);
            }
            matches += countMatches(value, targetSet);
        }
        return matches;
    }

    private static int countMatches(Object value, Set<Object> targets) {
        if (value == null) {
            return 0;
        }
        if (targets.contains(value)) {
            return 1;
        }
        int matches = 0;
        if (value instanceof Map<?, ?> map) {
            for (Object entryValue : map.values()) {
                if (targets.contains(entryValue)) {
                    matches++;
                }
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (targets.contains(item)) {
                    matches++;
                }
            }
        }
        return matches;
    }

    private static boolean isReachable(Object node, Set<Object> targets, Set<Object> visited, int depth) {
        if (node == null || depth > MAX_DEPTH || !visited.add(node)) {
            return false;
        }
        if (targets.contains(node)) {
            return true;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isReachable(entry.getKey(), targets, visited, depth + 1)
                        || isReachable(entry.getValue(), targets, visited, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (isReachable(item, targets, visited, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        // Bounded to this module's own object graph: a value observation's reachable types are all
        // dev.vertique.* records/classes (plus the Map/List branches above); JDK and Vert.x internals
        // (String, security claim internals, Buffer's own byte storage, etc.) are deliberately not
        // walked field-by-field, which is safe here because reference identity to a raw source could
        // only survive if this module's own types stored it directly or inside a Map/List.
        if (!node.getClass().getName().startsWith("dev.vertique")) {
            return false;
        }
        for (Field field : allInstanceFields(node.getClass())) {
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(node);
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException(impossible);
            }
            if (isReachable(value, targets, visited, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Declared instance fields of {@code type} and its superclasses, stopping at the first
     * non-{@code dev.vertique} superclass (e.g. {@code Object}, {@code Enum}, {@code Record}) so the
     * walk never attempts {@link Field#setAccessible} on a JDK-owned field a strong module boundary
     * may reject.
     */
    private static List<Field> allInstanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type;
                current != null && current.getName().startsWith("dev.vertique");
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    /**
     * A recording {@link McpToolValueObservation} session that deliberately retains the exact
     * reference it is handed — the documented callback-scoped obligation this task places on
     * implementors, not something the framework enforces.
     */
    static final class RecordingValueObservation implements McpToolValueObservation {
        private McpToolInputObservation retained;
        private int callCount;

        @Override
        public void onToolInput(McpToolInputObservation observation) {
            this.retained = observation;
            this.callCount++;
        }

        McpToolInputObservation retained() {
            return retained;
        }

        int callCount() {
            return callCount;
        }
    }
}
