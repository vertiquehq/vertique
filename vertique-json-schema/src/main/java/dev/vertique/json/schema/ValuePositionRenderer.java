// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import java.lang.reflect.AnnotatedType;
import java.util.Optional;
import java.util.Set;

/**
 * Renders the schema at a value position — a map value ({@link InputPropertyDescriber#describeMapLike}),
 * an any-setter extras value ({@link InputPropertyDescriber#describeExtras}), and a declared {@code
 * Optional<T>} content (T002, {@code D002}) — through one shared four-step pipeline (rest-023 T001;
 * {@code decisions/D001}, {@code D002}, {@code D004}):
 *
 * <ol>
 *   <li><strong>Profile override first</strong> (the F4 rule) — a declared {@code JsonSchemaTypeOverride}
 *       on the value type wins before its own reflective schema is ever built.
 *   <li><strong>The value's own schema</strong> — the value type's schema through {@code
 *       createDefinitionReference}; for a declared {@code Optional<T>} position (T002, {@code D002}),
 *       {@code T}'s own schema — not {@code Optional<T>}'s — so a profile override on {@code T} still
 *       wins first and {@code T}'s own declared constraints render exactly as they do for a
 *       non-{@code Optional} position; or, for an entry of {@link #UNCONSTRAINED_VALUE_TYPES} ({@code
 *       Object}, {@code JsonNode}, {@code TreeNode}) or a {@code null} value type — including a declared
 *       {@code Optional<T>} whose {@code T} is one of those types — an open position (no schema
 *       written; each caller decides how to render "open" for its own shape, exactly as {@code main}
 *       does today).
 *   <li><strong>Type-use constraint overlay</strong> — a hook that overlays a walk-vocabulary constraint
 *       declared on the position's own {@link AnnotatedType} (field, getter, creator parameter,
 *       any-setter), where one is supplied.
 *   <li><strong>Nullability</strong> — marks a schema nullable for an {@code Optional}-typed position,
 *       through {@link InputPropertyDescriber#markNullable}, the same helper the describer's own
 *       named-member callers ({@code fieldSchema}, {@code methodSchema}) already use.
 * </ol>
 *
 * <p>Steps 1 and 2 are reached together, through one {@code createDefinitionReference} call:
 * {@code ProfileOverrideDefinitionProvider}, registered ahead of {@link InputPropertyDescriber} in the
 * provider chain, applies a declared override before this renderer (or {@link InputPropertyDescriber}
 * itself) is ever consulted for the value type — so step 1 needs no separate lookup here.
 *
 * <p><strong>T001 wired steps 1–3; T002 ({@code D002}) activates step 4.</strong> Every caller passes a
 * {@code null} {@link AnnotatedType} (see {@link ValuePosition}), so step 3's own overlay hook ({@link
 * #overlayTypeUseConstraint}) is still a no-op regardless of its input — neither {@link
 * InputPropertyDescriber#describeMapLike} nor {@link InputPropertyDescriber#describeExtras} supplies a
 * non-{@code null} one yet, so both reproduce exactly the schema {@code main} rendered before T001's own
 * extraction for a non-{@code Optional} position (the behavior-preservation proof, TP-001). T002
 * detects a declared {@code Optional<T>} value position here: step 2 renders {@code T} itself — not
 * {@code Optional<T>} — through the same {@code createDefinitionReference} call, and step 4 then marks
 * the rendered node nullable through {@link InputPropertyDescriber#markNullable}, because the declared
 * type is nullable — null is admitted matching Jackson's own {@code Optional.empty()} binding. An
 * {@code Optional<T>} whose {@code T} is one of {@link #UNCONSTRAINED_VALUE_TYPES} stays an open
 * position (a {@code null} return), exactly like a non-{@code Optional} entry of one of those types.
 * T003 ({@code D001}) wires a real {@link AnnotatedType} through for a named map position and for an
 * any-setter's own value position (closing N16), and, for a {@code describeMapLike}-handled {@code Map}
 * subclass, an overlay source of {@code Class.getAnnotatedSuperclass()}/{@code
 * getAnnotatedInterfaces()} (security round-2 finding S4) rather than a member-position {@link
 * AnnotatedType}, which does not exist for that type-level reach. T004 ({@code D004}) is the first to
 * invoke the {@link InlineComposer} callback this task stores but never calls, to inline a bean-valued
 * subschema at a conjunction position.
 *
 * <p><strong>Collaborators (architecture round-2 condition C6).</strong> The renderer takes its
 * collaborators at construction — a {@link ValidatedProfile} (possibly {@code null}, as in {@link
 * InputPropertyDescriber} itself), a {@link ConstraintSource} supplement (possibly {@code null}, as in
 * the describer), and a describer-supplied {@link InlineComposer} — and holds no reference back to
 * {@link InputPropertyDescriber}: the dependency direction is one-way (describer &rarr; renderer). A
 * position is passed as one value, {@link ValuePosition}, rather than as several separate parameters.
 */
final class ValuePositionRenderer {

    /** Value types that accept every JSON value, and are therefore rendered as an open position. */
    private static final Set<Class<?>> UNCONSTRAINED_VALUE_TYPES = Set.of(Object.class, JsonNode.class, TreeNode.class);

    private final ValidatedProfile validatedProfile;
    private final ConstraintSource supplement;
    private final InlineComposer inlineComposer;

    /**
     * @param validatedProfile the validated, direction-filtered profile view, or {@code null} when none
     *                          was supplied to the generator; not yet consulted directly by this task —
     *                          the override step is already reached through {@code
     *                          createDefinitionReference} (see class Javadoc)
     * @param supplement        the Bean Validation metadata supplement, or {@code null} when no {@link
     *                          jakarta.validation.Validator} was supplied to the generator; not yet
     *                          consulted by this task's own pipeline steps
     * @param inlineComposer    the describer-supplied callback that inlines a bean value type's own
     *                          object schema at a position, stored for T004/T005's own conjunction and
     *                          inline paths; not yet called by this task's own pipeline steps
     */
    ValuePositionRenderer(
            ValidatedProfile validatedProfile, ConstraintSource supplement, InlineComposer inlineComposer) {
        this.validatedProfile = validatedProfile;
        this.supplement = supplement;
        this.inlineComposer = inlineComposer;
    }

    /**
     * Renders {@code position}'s own value schema: steps 1–4 of the class-level pipeline (step 4 only
     * for a declared {@code Optional<T>} position; see class Javadoc). Returns {@code null} when the
     * position is open (a {@code null} value type, one of {@link #UNCONSTRAINED_VALUE_TYPES}, or a
     * declared {@code Optional<T>} whose {@code T} is one of those) — the caller decides how to render
     * an open position for its own shape ({@link InputPropertyDescriber#describeMapLike} omits the
     * {@code additionalProperties} keyword entirely; {@link InputPropertyDescriber#describeExtras}
     * writes an explicit empty object — both preserved byte-for-byte from before this extraction).
     *
     * @param context  the active generation context
     * @param position the value position being rendered
     * @return the value's own schema, or {@code null} for an open position
     */
    JsonNode renderValueSchema(SchemaGenerationContext context, ValuePosition position) {
        JavaType valueType = position.valueType();
        if (valueType == null || UNCONSTRAINED_VALUE_TYPES.contains(valueType.getRawClass())) {
            return null;
        }
        // T002 (D002): a declared Optional<T> position renders T itself (not Optional<T>) through steps
        // 1+2 below, and is marked nullable by step 4 at the end — null is admitted because the
        // declared type is nullable, matching Jackson's own Optional.empty() binding (see class
        // Javadoc). AtomicReference is not widened to this branch: D002 is about Optional specifically.
        boolean optional = valueType.isReferenceType() && valueType.getRawClass() == Optional.class;
        JavaType renderedType = optional ? valueType.getReferencedType() : valueType;
        if (renderedType == null || UNCONSTRAINED_VALUE_TYPES.contains(renderedType.getRawClass())) {
            return null;
        }
        // Steps 1+2: reached together through createDefinitionReference (see class Javadoc).
        JsonNode schema = context.createDefinitionReference(InputPropertyDescriber.resolve(context, renderedType));
        // Step 3: the type-use constraint overlay hook. Extracted here, but inert in this task — every
        // T001 caller passes a null AnnotatedType, and overlayTypeUseConstraint below does nothing
        // regardless of its input until T003 implements the walk vocabulary (see class Javadoc).
        overlayTypeUseConstraint(schema, position.annotatedType());
        // Step 4: nullability, active only for a declared Optional<T> position (T002, D002).
        if (optional) {
            InputPropertyDescriber.markNullable((ObjectNode) schema);
        }
        return schema;
    }

    /**
     * Step 3 of the class-level pipeline: overlays a walk-vocabulary type-use constraint declared on
     * {@code annotatedType} onto {@code schema}. A no-op in this task regardless of whether {@code
     * annotatedType} is {@code null} or non-null — no T001 caller supplies a non-null one, and no walk
     * vocabulary is implemented yet; T003 ({@code D001}) is where this hook first does something (see
     * class Javadoc).
     *
     * @param schema        the value's own schema, already rendered by step 2
     * @param annotatedType the position's own {@link AnnotatedType}, or {@code null} when the position
     *                      carries none (every T001 caller)
     */
    private static void overlayTypeUseConstraint(JsonNode schema, AnnotatedType annotatedType) {
        // Intentionally inert in this task; see this method's own Javadoc and the class Javadoc.
    }

    /**
     * Inlines a bean value type's own object schema at a value position, in place of a {@code $ref} —
     * the mechanism D004's own conjunction rule and D005's own member-level inline path share. Not
     * called by any pipeline step in this task; see class Javadoc.
     */
    @FunctionalInterface
    interface InlineComposer {

        /**
         * @param beanType the bean value type to inline
         * @param context  the active generation context
         * @return the inlined object schema
         */
        JsonNode inline(JavaType beanType, SchemaGenerationContext context);
    }

    /**
     * One value position: the value's declared type, its own {@link AnnotatedType} where one exists
     * (field, getter, creator parameter, any-setter — {@code null} otherwise, which is every position
     * this task itself renders), and the position's own member name, carried for a future diagnostic
     * (not read by this task's own pipeline steps).
     *
     * @param valueType     the value's declared {@link JavaType}, or {@code null} when the position has
     *                      none (an undeclared map content type)
     * @param annotatedType the position's own {@link AnnotatedType}, or {@code null}
     * @param memberName    the position's own member name, for a future diagnostic, or {@code null}
     */
    record ValuePosition(JavaType valueType, AnnotatedType annotatedType, String memberName) {}
}
