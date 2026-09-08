// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import dev.vertique.codegen.support.Identifiers;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;

/**
 * INTERNAL framework seam — processor-authoring substrate consumed by sibling framework modules;
 * not an application contract and outside the maturity promise. An application uses the wiring
 * annotations this module documents and never calls this type.
 *
 * <p>Shared codegen-time helpers for reading {@code @ConditionalOnProperty} annotation mirrors and
 * emitting {@code dev.vertique.core.config.PropertyCondition[]} array literals.
 *
 * <p>Both {@code vertique-codegen-services} and {@code vertique-codegen-jaxrs} consume
 * {@code @ConditionalOnProperty} (and its {@code @ConditionalOnProperties} repeatable container)
 * to generate runtime condition arrays. This class centralizes the reading + emission logic so
 * the two emitters stay in lockstep on:
 * <ul>
 *   <li>repeatable annotation handling (single + container forms via {@link Element#getAnnotationMirrors()},
 *       NOT {@code Element.getAnnotation()} which silently misses the container case);</li>
 *   <li>default attribute values ({@code havingValue="true"}, {@code matchIfMissing=false});</li>
 *   <li>generated {@code new PropertyCondition[] { ... }} initializer shape;</li>
 *   <li>{@code SCREAMING_SNAKE_CASE} constant-name derivation (delegating to
 *       {@link Identifiers#constantName(String)}).</li>
 * </ul>
 *
 * <p>All methods are stateless. Instances are bound to an {@link AnnotationMirrors} helper so they
 * pick up the active processing environment's element-utility view of annotation values.
 */
public final class Conditions {

    /** FQN of {@code dev.vertique.codegen.ConditionalOnProperty}. */
    public static final String CONDITIONAL_ON_PROPERTY_FQN = "dev.vertique.codegen.ConditionalOnProperty";

    /** FQN of {@code dev.vertique.codegen.ConditionalOnProperties}. */
    public static final String CONDITIONAL_ON_PROPERTIES_FQN = "dev.vertique.codegen.ConditionalOnProperties";

    /** {@code dev.vertique.core.config.PropertyCondition} {@link ClassName} used in emitted source. */
    public static final ClassName PROPERTY_CONDITION = ClassName.get("dev.vertique.core.config", "PropertyCondition");

    private final AnnotationMirrors mirrors;

    /**
     * Constructs a {@code Conditions} helper bound to the given {@link AnnotationMirrors}.
     *
     * @param mirrors the annotation-mirrors helper from the active codegen context;
     *                must not be {@code null}
     */
    public Conditions(AnnotationMirrors mirrors) {
        this.mirrors = mirrors;
    }

    /**
     * A single condition triple extracted from a {@code @ConditionalOnProperty} mirror.
     *
     * @param name           the dotted-path configuration property name
     * @param havingValue    the expected scalar value (string-form); defaults to {@code "true"}
     * @param matchIfMissing whether a missing property satisfies the condition; defaults to
     *                       {@code false}
     */
    public record ConditionData(String name, String havingValue, boolean matchIfMissing) {}

    /**
     * Reads all {@code @ConditionalOnProperty} annotations on the given element, including the
     * {@code @ConditionalOnProperties} repeatable container form, and returns each condition's
     * {@code (name, havingValue, matchIfMissing)} triple in declaration order.
     *
     * <p>Returns an empty list when the element carries no conditional annotations.
     *
     * @param element the element to inspect; must not be {@code null}
     * @return the conditions in declaration order; empty when none are present
     */
    public List<ConditionData> read(Element element) {
        List<ConditionData> result = new ArrayList<>();
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            String fqn = mirror.getAnnotationType().asElement().toString();
            if (CONDITIONAL_ON_PROPERTY_FQN.equals(fqn)) {
                result.add(extract(mirror));
            } else if (CONDITIONAL_ON_PROPERTIES_FQN.equals(fqn)) {
                for (AnnotationValue av : mirrors.attributeArray(mirror, "value")) {
                    if (av.getValue() instanceof AnnotationMirror nested) {
                        result.add(extract(nested));
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns {@code true} if the given element carries any {@code @ConditionalOnProperty} or
     * {@code @ConditionalOnProperties} annotation. Mirror-based lookup correctly handles the
     * {@code @Repeatable} container form.
     *
     * @param element the element to inspect; must not be {@code null}
     * @return {@code true} if a conditional annotation is present
     */
    public static boolean isConditional(Element element) {
        return AnnotationMirrors.isPresent(element, CONDITIONAL_ON_PROPERTY_FQN)
                || AnnotationMirrors.isPresent(element, CONDITIONAL_ON_PROPERTIES_FQN);
    }

    /**
     * Builds an array initializer of the form
     * {@code new PropertyCondition[] { new PropertyCondition($S, $S, $L), ... }} suitable for use
     * as a {@code static final PropertyCondition[]} field initializer in generated source.
     *
     * @param conditions the conditions to emit; must not be {@code null}; may be empty (yields
     *                   {@code new PropertyCondition[] {}})
     * @return a JavaPoet {@link CodeBlock} containing the array initializer
     */
    public static CodeBlock arrayInitializer(List<ConditionData> conditions) {
        CodeBlock.Builder b = CodeBlock.builder().add("new $T[] {", PROPERTY_CONDITION);
        for (int i = 0; i < conditions.size(); i++) {
            ConditionData c = conditions.get(i);
            if (i > 0) {
                b.add(",");
            }
            b.add("\n    new $T($S, $S, $L)", PROPERTY_CONDITION, c.name(), c.havingValue(), c.matchIfMissing());
        }
        b.add("\n}");
        return b.build();
    }

    /**
     * Derives the {@code SCREAMING_SNAKE_CASE} {@code PropertyCondition[]} constant name for a
     * base name (typically a method or impl simple name), suffixed with {@code _CONDITIONS}.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "userResourceBinding"} → {@code "USER_RESOURCE_BINDING_CONDITIONS"}</li>
     *   <li>{@code "UserServiceSandbox"} → {@code "USER_SERVICE_SANDBOX_CONDITIONS"}</li>
     * </ul>
     *
     * @param baseName the base name to convert; must not be {@code null}
     * @return the SCREAMING_SNAKE_CASE constant name with the {@code _CONDITIONS} suffix
     */
    public static String constantName(String baseName) {
        return Identifiers.constantName(baseName) + "_CONDITIONS";
    }

    private ConditionData extract(AnnotationMirror mirror) {
        String name = mirrors.attribute(mirror, "name", String.class).orElse("");
        String havingValue =
                mirrors.attribute(mirror, "havingValue", String.class).orElse("true");
        boolean matchIfMissing =
                mirrors.attribute(mirror, "matchIfMissing", Boolean.class).orElse(false);
        return new ConditionData(name, havingValue, matchIfMissing);
    }
}
