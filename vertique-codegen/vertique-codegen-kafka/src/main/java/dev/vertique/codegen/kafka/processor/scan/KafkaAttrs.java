// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.scan;

import dev.vertique.codegen.CodegenContext;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.VariableElement;

/**
 * Shared annotation-attribute name constants and helper for reading enum attributes from Kafka
 * annotation mirrors.
 *
 * <p>Both {@link KafkaListenerScanner} and {@link KafkaSourceScanner} read the same set of
 * {@code @KafkaListener} / {@code @KafkaSource} attribute names and need identical logic to read
 * APT-represented enum constants. This class centralises those constants and the
 * {@link #readEnumConstant} helper so the two scanners stay in sync without duplication.
 *
 * <p>This class is package-private and intended only for use within the
 * {@code dev.vertique.codegen.kafka.processor.scan} package.
 */
final class KafkaAttrs {

    // --- @KafkaListener / @KafkaSource shared attribute names ---

    /** Attribute name for the binding name ({@code name}). */
    static final String ATTR_NAME = "name";

    /** Attribute name for the Kafka topic ({@code topic}). */
    static final String ATTR_TOPIC = "topic";

    /** Attribute name for the consumer group id ({@code groupId}). */
    static final String ATTR_GROUP_ID = "groupId";

    /** Attribute name for the error strategy enum ({@code errorStrategy}). */
    static final String ATTR_ERROR_STRATEGY = "errorStrategy";

    /** Attribute name for the commit strategy enum ({@code commitStrategy}). */
    static final String ATTR_COMMIT_STRATEGY = "commitStrategy";

    /** Attribute name for the dead-letter topic ({@code deadLetterTopic}). */
    static final String ATTR_DEAD_LETTER_TOPIC = "deadLetterTopic";

    /**
     * Fully-qualified name of the {@code @JsonProfile} annotation
     * ({@code dev.vertique.core.json.JsonProfile}).
     *
     * <p>Read by FQN string match (rather than as a compile dependency) so the processor jar need
     * not depend on {@code vertique-core}. {@code @JsonProfile} is the sole per-binding profile
     * selection annotation on a {@code @KafkaListener} type.
     */
    static final String ATTR_JSON_PROFILE_FQN = "dev.vertique.core.json.JsonProfile";

    /** Attribute name for the {@code @JsonProfile} value ({@code value}). */
    static final String ATTR_JSON_PROFILE_VALUE = "value";

    // --- Utility class: not instantiable ---

    private KafkaAttrs() {}

    /**
     * Reads an enum constant attribute from an annotation mirror, returning the constant's simple
     * name. Enum values are represented in the APT mirror as {@link VariableElement} instances.
     *
     * @param ctx           the shared codegen context used to read annotation attributes
     * @param mirror        the annotation mirror to read from
     * @param attributeName the attribute name
     * @param defaultValue  the fallback constant name when the attribute is absent
     * @return the enum constant name (e.g. {@code "SKIP"})
     */
    static String readEnumConstant(
            CodegenContext ctx, AnnotationMirror mirror, String attributeName, String defaultValue) {
        return ctx.annotations()
                .attribute(mirror, attributeName, VariableElement.class)
                .map(v -> v.getSimpleName().toString())
                .orElse(defaultValue);
    }
}
