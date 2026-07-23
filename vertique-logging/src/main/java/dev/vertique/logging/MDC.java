// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging;

import java.util.Map;

/**
 * Logging-module convenience facade over the core MDC substrate.
 *
 * <p>All methods delegate directly to {@link MDCContexts}, which is backed by the single shared
 * {@link dev.vertique.core.context.ContextHolder} slot registered by
 * {@link dev.vertique.context.ContextLocalServiceProvider}. Applications should prefer
 * {@code MDCContexts} for any code that lives in {@code vertique-core} or its dependents;
 * {@code MDC} is provided for callers that are scoped to the logging module and for backward
 * compatibility with existing call sites.
 *
 * <p><b>Fail-fast writes:</b> {@link #put}, {@link #remove}, {@link #clear}, and
 * {@link #setContextMap} (when the map is non-empty) throw {@link IllegalStateException} when
 * called outside a Vert.x duplicated context. This matches the invariant enforced by
 * {@code DefaultContextHolder}.
 *
 * <p><b>Lenient reads:</b> {@link #get} and {@link #getCopyOfContextMap} return {@code null} /
 * empty map outside a Vert.x context rather than throwing.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * MDC.put("requestId", uuid);
 * MDC.put("userId", userId);
 * log.info("Processing request");  // requestId and userId available in log appender
 * MDC.clear();
 * }</pre>
 *
 * <p>For integration with SLF4J/Logback, call {@link #syncToSlf4j()} before logging, or configure
 * the logging module to bridge Vert.x MDC to SLF4J MDC automatically via
 * {@link dev.vertique.logging.logback.VertxAwareAppender}.
 */
public final class MDC {

    private MDC() {
        // Utility class
    }

    // --- Write operations (fail-fast on non-duplicated context) ---

    /**
     * Associates a value with a key in the current Vert.x context's MDC.
     *
     * @param key   the diagnostic key; must not be {@code null}
     * @param value the diagnostic value; must not be {@code null}
     * @throws IllegalStateException if called outside a Vert.x duplicated context
     */
    public static void put(String key, String value) {
        MDCContexts.put(key, value);
    }

    /**
     * Removes a key from the current Vert.x context's MDC.
     *
     * @param key the diagnostic key to remove; must not be {@code null}
     * @throws IllegalStateException if called outside a Vert.x duplicated context
     */
    public static void remove(String key) {
        MDCContexts.remove(key);
    }

    /**
     * Sets the entire MDC map for the current Vert.x context, replacing any existing entries.
     * Useful for restoring MDC from a saved snapshot (e.g., across event bus boundaries).
     *
     * <p>Delegates to {@link MDCContexts#clear()} followed by {@link MDCContexts#putAll(Map)} when
     * {@code values} is non-{@code null} and non-empty.
     *
     * @param values the new entries; may be {@code null} or empty to clear
     * @throws IllegalStateException if called outside a Vert.x duplicated context (when non-empty)
     */
    public static void setContextMap(Map<String, String> values) {
        MDCContexts.clear();
        if (values != null && !values.isEmpty()) {
            MDCContexts.putAll(values);
        }
    }

    /**
     * Clears all entries from the current Vert.x context's MDC.
     *
     * @throws IllegalStateException if called outside a Vert.x duplicated context
     */
    public static void clear() {
        MDCContexts.clear();
    }

    // --- Read operations (lenient) ---

    /**
     * Retrieves the value associated with a key from the current Vert.x context's MDC.
     *
     * @param key the diagnostic key; must not be {@code null}
     * @return the value, or {@code null} if not found or no Vert.x context is active
     */
    public static String get(String key) {
        return MDCContexts.get(key);
    }

    /**
     * Returns an immutable copy of the current MDC map.
     *
     * @return immutable copy of the MDC entries, or an empty map if no context or no entries
     */
    public static Map<String, String> getCopyOfContextMap() {
        return MDCContexts.copy();
    }

    // --- SLF4J bridge ---

    /**
     * Synchronizes the current Vert.x MDC to SLF4J's thread-local MDC.
     *
     * <p>Clears SLF4J's thread-local MDC first, then copies all current Vert.x MDC entries into
     * it. Call this before logging if using SLF4J with a non-Vert.x-aware appender.
     */
    public static void syncToSlf4j() {
        Map<String, String> copy = MDCContexts.copy();
        org.slf4j.MDC.clear();
        if (!copy.isEmpty()) {
            copy.forEach(org.slf4j.MDC::put);
        }
    }
}
