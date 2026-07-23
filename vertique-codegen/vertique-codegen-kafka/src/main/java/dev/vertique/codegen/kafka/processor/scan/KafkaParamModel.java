// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.scan;

import javax.lang.model.type.TypeMirror;

/**
 * Represents a single classified parameter from a {@code @KafkaHandler} method.
 *
 * <p>Each parameter is either a payload (the deserialized record value) or a dispatch-context
 * injection (e.g. {@code KafkaRecordContext}, {@code SecurityContext}).
 *
 * @param name      the parameter name from the method signature (for diagnostics)
 * @param source    the dispatch source; one of {@link #SOURCE_PAYLOAD} or
 *                  {@link #SOURCE_DISPATCH_CONTEXT}
 * @param type      the declared type mirror of the parameter
 * @param lookupKey the context-map key used when {@code source} is {@code DISPATCH_CONTEXT};
 *                  {@code null} for {@code PAYLOAD} parameters
 */
public record KafkaParamModel(String name, String source, TypeMirror type, String lookupKey) {

    /** The {@code ParamSource.PAYLOAD} constant name. */
    public static final String SOURCE_PAYLOAD = "PAYLOAD";

    /** The {@code ParamSource.DISPATCH_CONTEXT} constant name. */
    public static final String SOURCE_DISPATCH_CONTEXT = "DISPATCH_CONTEXT";

    /**
     * Convenience factory for a {@code PAYLOAD} parameter.
     *
     * @param name the parameter name
     * @param type the parameter type
     * @return a new {@code KafkaParamModel} with {@code source = PAYLOAD} and
     *         {@code lookupKey = null}
     */
    public static KafkaParamModel payload(String name, TypeMirror type) {
        return new KafkaParamModel(name, SOURCE_PAYLOAD, type, null);
    }

    /**
     * Convenience factory for a {@code DISPATCH_CONTEXT} parameter.
     *
     * @param name      the parameter name
     * @param type      the parameter type
     * @param lookupKey the context-map lookup key
     * @return a new {@code KafkaParamModel} with {@code source = DISPATCH_CONTEXT}
     */
    public static KafkaParamModel dispatchContext(String name, TypeMirror type, String lookupKey) {
        return new KafkaParamModel(name, SOURCE_DISPATCH_CONTEXT, type, lookupKey);
    }

    /**
     * Returns {@code true} if this parameter is sourced from the record's deserialized payload.
     *
     * @return {@code true} when {@code source} equals {@code "PAYLOAD"}
     */
    public boolean isPayload() {
        return SOURCE_PAYLOAD.equals(source);
    }
}
