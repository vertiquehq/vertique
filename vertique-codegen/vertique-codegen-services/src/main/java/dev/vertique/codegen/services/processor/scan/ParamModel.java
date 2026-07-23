// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.services.processor.scan;

import javax.lang.model.type.TypeMirror;

/**
 * Represents a single classified parameter from a service contract or handler method.
 *
 * <p>Each param has a source that determines how the dispatcher resolves its value at runtime:
 * <ul>
 *   <li>{@code PAYLOAD} — the parameter is extracted from the event bus message payload.</li>
 *   <li>{@code DISPATCH_CONTEXT} — the parameter is looked up from the per-dispatch context
 *       map using {@code lookupKey}.</li>
 * </ul>
 *
 * @param name      the parameter name from the method signature (for diagnostics)
 * @param source    the dispatch source; one of the {@code "PAYLOAD"} or {@code "DISPATCH_CONTEXT"}
 *                  string values matching {@code ServiceMethodMeta.ParamSource} enum constants
 * @param type      the declared type mirror of the parameter
 * @param lookupKey the context-map key used when {@code source} is {@code DISPATCH_CONTEXT};
 *                  {@code null} for {@code PAYLOAD} parameters
 */
public record ParamModel(String name, String source, TypeMirror type, String lookupKey) {

    /** The {@code ParamSource.PAYLOAD} enum constant name. */
    public static final String SOURCE_PAYLOAD = "PAYLOAD";

    /** The {@code ParamSource.DISPATCH_CONTEXT} enum constant name. */
    public static final String SOURCE_DISPATCH_CONTEXT = "DISPATCH_CONTEXT";

    /**
     * Convenience factory for a {@code PAYLOAD} parameter.
     *
     * @param name the parameter name
     * @param type the parameter type
     * @return a new {@code ParamModel} with {@code source = PAYLOAD} and {@code lookupKey = null}
     */
    public static ParamModel payload(String name, TypeMirror type) {
        return new ParamModel(name, SOURCE_PAYLOAD, type, null);
    }

    /**
     * Convenience factory for a {@code DISPATCH_CONTEXT} parameter.
     *
     * @param name      the parameter name
     * @param type      the parameter type
     * @param lookupKey the context-map lookup key
     * @return a new {@code ParamModel} with {@code source = DISPATCH_CONTEXT}
     */
    public static ParamModel dispatchContext(String name, TypeMirror type, String lookupKey) {
        return new ParamModel(name, SOURCE_DISPATCH_CONTEXT, type, lookupKey);
    }

    /**
     * Returns {@code true} if this parameter is sourced from the event bus message payload.
     *
     * @return {@code true} when {@code source} equals {@code "PAYLOAD"}
     */
    public boolean isPayload() {
        return SOURCE_PAYLOAD.equals(source);
    }
}
