// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedTypeVariable;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Map;
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
 *       createDefinitionReference} (or, when a type-use overlay applies anywhere in this position's own
 *       — possibly nested — content, the context's own inline definition creation instead; see "N1" below);
 *       for a declared {@code Optional<T>} position (T002, {@code D002}), {@code T}'s own schema — not
 *       {@code Optional<T>}'s — so a profile override on {@code T} still wins first and {@code T}'s own
 *       declared constraints render exactly as they do for a non-{@code Optional} position; or, for an
 *       entry of {@link #UNCONSTRAINED_VALUE_TYPES} ({@code Object}, {@code JsonNode}, {@code TreeNode})
 *       or a {@code null} value type — including a declared {@code Optional<T>} whose {@code T} is one of
 *       those types — an open position (no schema written; each caller decides how to render "open" for
 *       its own shape, exactly as {@code main} does today).
 *   <li><strong>Type-use constraint overlay</strong> — overlays a walk-vocabulary constraint declared on
 *       the position's own {@link AnnotatedType} (field, getter, creator parameter, any-setter, or a
 *       {@code Map} subclass's own supertype chain), where one is supplied.
 *   <li><strong>Nullability</strong> — marks a schema nullable for an {@code Optional}-typed position,
 *       through {@link InputPropertyDescriber#markNullable}, the same helper the describer's own
 *       named-member callers ({@code fieldSchema}, {@code methodSchema}) already use.
 * </ol>
 *
 * <p><strong>N1 (spec-pass round 2): the overlay is never written into a shared or referenced
 * definition node.</strong> Victools may resolve two different positions of the exact same underlying
 * {@code Map<K,V>} type to <em>one</em> shared {@code $defs} entry (measured: two members of the same
 * {@code Map<String,String>} type, one carrying a type-use constraint on {@code V} and one not, sharing
 * one {@code "Map(String,String)"} definition) — {@link InputPropertyDescriber#provideCustomSchemaDefinition}
 * receives only the resolved <em>type</em>, never the member that triggered the lookup, so a shared
 * definition cannot be told apart per position from inside it. This renderer resolves the leak instead
 * of narrowing that sharing: {@link #renderValueSchema} decides, <em>before</em> ever asking the schema
 * library for a definition, whether {@code position}'s own (possibly nested, for a {@code Map<K,
 * Map<K,V>>} content) type-use overlay is non-empty anywhere; when it is, every level of that content is
 * rendered through {@code context.createDefinition} — an owned copy, never registered under {@code
 * $defs} — with the overlay applied onto that owned copy; when it is not, the position renders through
 * {@code context.createDefinitionReference} exactly as before this task, so every existing golden with
 * no overlay stays byte-identical. A named member's own map position is never resolved through the
 * schema library's shared, per-type custom-definition lookup at all when it carries an overlay: {@link
 * InputPropertyDescriber} short-circuits that member's own schema construction before ever asking
 * Victools for a {@code FieldScope}/{@code MethodScope} definition (see {@code propertySchema}), so the
 * overlay never reaches a code path Victools could cache or share across sibling members of the same
 * underlying {@code Map} type. A type-level overlay (a {@code Map} subclass's own supertype chain) is
 * the one exception that is safe to leave on the ordinary, shared path: that overlay is intrinsic to the
 * <em>type</em>, identical for every member that references it, so sharing it changes nothing.
 *
 * <p>Steps 1 and 2 are reached together: {@code ProfileOverrideDefinitionProvider}, registered ahead of
 * {@link InputPropertyDescriber} in the provider chain, applies a declared override before this renderer
 * (or {@link InputPropertyDescriber} itself) is ever consulted for the value type — so step 1 needs no
 * separate lookup here, whether the value's own schema is created as a reference or inline.
 *
 * <p><strong>T001 wired steps 1–3 (inert); T002 ({@code D002}) activated step 4; T003 ({@code D001})
 * activates step 3.</strong> T002 detects a declared {@code Optional<T>} value position here: step 2
 * renders {@code T} itself — not {@code Optional<T>} — through the same value-schema step, and step 4
 * then marks the rendered node nullable through {@link InputPropertyDescriber#markNullable}, because the
 * declared type is nullable — null is admitted matching Jackson's own {@code Optional.empty()} binding.
 * An {@code Optional<T>} whose {@code T} is one of {@link #UNCONSTRAINED_VALUE_TYPES} stays an open
 * position (a {@code null} return), exactly like a non-{@code Optional} entry of one of those types. T003
 * wires a real {@link AnnotatedType} through for a named map position ({@link #mapValueSlotOfMember(Member)}),
 * for an any-setter's own value position (closing N16, the same helper), and, for a {@code
 * describeMapLike}-handled {@code Map} subclass, an overlay source of {@link
 * #mapValueSlotOfClass(Class)} (security round-2 finding S4) rather than a member-position {@link
 * AnnotatedType}, which does not exist for that type-level reach. Every one of these entry points
 * resolves the {@code V} slot through {@link #mapValueSlot(AnnotatedType)} — the {@code java.util.Map}
 * type-parameter binding Jackson itself resolves along the declaration's own supertype chain, never the
 * fixed positional index {@code [1]} of a declaration site's own written type arguments, which silently
 * drops or misapplies the overlay for a {@code Map} subclass that reorders its own type parameters
 * relative to {@code Map<K,V>} (review round 1, Critical: {@code class Reordered<V, K> extends
 * LinkedHashMap<K, V>} binds {@code V} at index 0, not 1). A nested map value (N9) is
 * handled by {@link #renderValueSchema} recursing into its own content when the rendered type is itself
 * map-like and its own nested content carries an overlay. T004 ({@code D004}) is the first to invoke the
 * {@link InlineComposer} callback this task stores but never calls, to inline a bean-valued subschema at
 * a conjunction position.
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
        AnnotatedType annotatedType = position.annotatedType();
        // Steps 1+2+3 (N1, see class Javadoc): a position (or its own nested map content) carrying an
        // overlay anywhere is rendered entirely through the context's own inline definition creation, so
        // the overlay is never written into a node the schema library could share across positions; a
        // position with no overlay anywhere renders through createDefinitionReference exactly as T001
        // left it, so every existing golden with no overlay stays byte-identical.
        JsonNode schema = hasOverlayAnywhere(renderedType, annotatedType)
                ? renderInlineWithOverlay(context, renderedType, annotatedType)
                : context.createDefinitionReference(InputPropertyDescriber.resolve(context, renderedType));
        // Step 4: nullability, active only for a declared Optional<T> position (T002, D002).
        if (optional) {
            InputPropertyDescriber.markNullable((ObjectNode) schema);
        }
        return schema;
    }

    /**
     * Whether {@code type}'s own value position carries a walk-vocabulary type-use overlay, directly on
     * {@code annotatedType} or, recursively, anywhere in its own nested map content (N9).
     *
     * @param type          the position's own value type, or {@code null}
     * @param annotatedType the position's own {@link AnnotatedType}, or {@code null}
     * @return whether an overlay applies at this position or at any of its own nested map positions
     */
    static boolean hasOverlayAnywhere(JavaType type, AnnotatedType annotatedType) {
        if (type == null || annotatedType == null) {
            return false;
        }
        ResolvedConstraints direct = WalkConstraintSource.INSTANCE.forTypeUse(
                annotatedType, ConstraintValueKind.fromJavaType(type.getRawClass()));
        if (!direct.additions().isEmpty()) {
            return true;
        }
        if (!type.isMapLikeType()) {
            return false;
        }
        return hasOverlayAnywhere(type.getContentType(), mapValueSlot(annotatedType));
    }

    /**
     * Renders {@code renderedType}'s own schema as an owned, never-shared node (N1), recursing into its
     * own nested map content when {@code renderedType} is itself map-like, and applying every
     * walk-vocabulary keyword {@code annotatedType} carries onto the rendered node through {@link
     * InputPropertyDescriber#applyCorrection} — the same stricter-wins keyword-merge semantics the
     * describer's own unscoped-member path uses, so an overlay never loosens a keyword the value's own
     * schema already carries.
     *
     * @param context       the active generation context
     * @param renderedType  the position's own value type (already unwrapped from a declared {@code
     *                      Optional<T>}, where applicable)
     * @param annotatedType the position's own {@link AnnotatedType}, possibly {@code null} (a nested map
     *                      content position may inherit a non-null container overlay while carrying none
     *                      of its own)
     * @return the rendered, owned node
     */
    private JsonNode renderInlineWithOverlay(
            SchemaGenerationContext context, JavaType renderedType, AnnotatedType annotatedType) {
        ObjectNode schema;
        if (renderedType.isMapLikeType()) {
            schema = context.getGeneratorConfig().createObjectNode();
            schema.put("type", "object");
            JavaType content = renderedType.getContentType();
            AnnotatedType nestedAnnotatedType = mapValueSlot(annotatedType);
            JsonNode valueSchema = renderValueSchema(context, new ValuePosition(content, nestedAnnotatedType, null));
            if (valueSchema != null) {
                schema.set("additionalProperties", valueSchema);
            }
        } else {
            schema = context.createDefinition(InputPropertyDescriber.resolve(context, renderedType));
        }
        ResolvedConstraints overlay = annotatedType == null
                ? ResolvedConstraints.NONE
                : WalkConstraintSource.INSTANCE.forTypeUse(
                        annotatedType, ConstraintValueKind.fromJavaType(renderedType.getRawClass()));
        for (Map.Entry<String, Object> entry : overlay.additions().entrySet()) {
            InputPropertyDescriber.applyCorrection(schema, entry.getKey(), entry.getValue());
        }
        return schema;
    }

    /**
     * The {@link AnnotatedType} of a {@code Map<K,V>}-declaring member's own value position ({@code V}),
     * given the member's own raw {@link Field} or {@link Method} — a getter's own annotated return type,
     * or a setter's own sole annotated parameter type. rest-023 T003 ({@code D001}): the one new
     * reflection surface this task adds, since {@link com.fasterxml.jackson.databind.introspect.AnnotatedMember}
     * carries no {@link AnnotatedType} of its own (verified against jackson-databind 2.22.2).
     *
     * @param raw the member's own raw {@link Field} or {@link Method}, or another {@link Member} kind
     *            (for which this method answers {@code null})
     * @return {@code V}'s own {@link AnnotatedType}, resolved through {@link #mapValueSlot(AnnotatedType)},
     *     or {@code null} when {@code raw} carries none (a raw, non-parameterized {@code Map} declaration,
     *     or a {@link Member} kind this method does not recognize)
     */
    static AnnotatedType mapValueSlotOfMember(Member raw) {
        AnnotatedType container;
        if (raw instanceof Field field) {
            container = field.getAnnotatedType();
        } else if (raw instanceof Method method) {
            container = method.getParameterCount() == 0
                    ? method.getAnnotatedReturnType()
                    : method.getAnnotatedParameterTypes()[0];
        } else {
            return null;
        }
        return mapValueSlot(container);
    }

    /**
     * The {@link AnnotatedType} of a creator parameter's own declared {@code Map<K,V>} value position
     * ({@code V}), read off the owning {@link Executable}'s own annotated parameter types at the
     * parameter's own index — the creator-parameter counterpart to {@link #mapValueSlotOfMember(Member)}
     * for a parameter, which carries no {@link Field}/{@link Method} of its own.
     *
     * @param owner the parameter's owning constructor or method, or another {@link Member} kind (for
     *              which this method answers {@code null})
     * @param index the parameter's own index
     * @return {@code V}'s own {@link AnnotatedType}, resolved through {@link #mapValueSlot(AnnotatedType)},
     *     or {@code null}
     */
    static AnnotatedType mapValueSlotOfParameter(Member owner, int index) {
        if (!(owner instanceof Executable executable)) {
            return null;
        }
        AnnotatedType[] parameters = executable.getAnnotatedParameterTypes();
        if (index < 0 || index >= parameters.length) {
            return null;
        }
        return mapValueSlot(parameters[index]);
    }

    /**
     * The {@link AnnotatedType} that carries {@code java.util.Map}'s own {@code V} type-parameter's
     * annotations for {@code declared} — resolved through {@code declared}'s own type-parameter binding
     * along its supertype chain, never through the fixed positional index {@code [1]} of {@code
     * declared}'s own written type arguments (review round 1, Critical): a {@code Map} subclass that
     * reorders its own type parameters relative to {@code Map<K,V>} (e.g. {@code class Reordered<V, K>
     * extends LinkedHashMap<K, V>}) binds {@code V} at a different index than a same-order declaration.
     *
     * <p>Recursive over {@code declared}'s own erased class's supertype chain (bounded by Java's acyclic
     * inheritance):
     *
     * <ol>
     *   <li>When {@code declared}'s own erased class is {@code Map} itself, the slot is {@code declared}'s
     *       own second type argument (when {@code declared} is parameterized; {@code null} for a raw,
     *       non-parameterized {@code Map} declaration).
     *   <li>Otherwise, find the one {@code Map}-assignable annotated supertype of {@code declared}'s own
     *       erased class ({@link #mapAssignableSupertype(Class)}) and recurse into it.
     *   <li>When the recursive result is an {@link AnnotatedTypeVariable} bound by one of {@code
     *       declared}'s own erased class's own type parameters, the slot is bound at {@code declared}'s
     *       own declaration site: return {@code declared}'s own type argument at that parameter's index
     *       (when {@code declared} is parameterized; {@code null} for a raw use).
     *   <li>Otherwise the slot is already concrete (fixed at some supertype along the chain, e.g. {@code
     *       class Tags extends HashMap<String, @Size(max = 3) String>} fixes {@code V} there): return it
     *       unchanged, since its own annotations are the constraint source.
     * </ol>
     *
     * @param declared the {@code Map<K,V>}-or-subclass declaration site's own {@link AnnotatedType},
     *                  possibly {@code null}
     * @return {@code V}'s own {@link AnnotatedType}, or {@code null}
     */
    static AnnotatedType mapValueSlot(AnnotatedType declared) {
        if (declared == null) {
            return null;
        }
        Class<?> raw = erase(declared);
        if (raw == null) {
            return null;
        }
        if (raw == Map.class) {
            return declared instanceof AnnotatedParameterizedType parameterized
                    ? parameterized.getAnnotatedActualTypeArguments()[1]
                    : null;
        }
        AnnotatedType supertype = mapAssignableSupertype(raw);
        if (supertype == null) {
            return null;
        }
        AnnotatedType slot = mapValueSlot(supertype);
        int index = ownTypeParameterIndex(slot, raw);
        if (index < 0) {
            return slot;
        }
        return declared instanceof AnnotatedParameterizedType parameterized
                ? parameterized.getAnnotatedActualTypeArguments()[index]
                : null;
    }

    /**
     * The overlay source {@link AnnotatedType} for a {@code Map}-like type reached without a declaration
     * site (security round-2 finding S4) — a {@code describeMapLike}-handled type-level reach, which
     * carries no member-position {@link AnnotatedType} of its own. Performs the same supertype-chain walk
     * and type-parameter-binding resolution as {@link #mapValueSlot(AnnotatedType)}, starting one level
     * up: {@code raw}'s own {@link #mapAssignableSupertype(Class)}, recursively resolved, then re-bound
     * at {@code raw} only when the resolved slot is one of {@code raw}'s own type parameters and {@code
     * raw} itself carries a declaration site to read an argument off — which it does not, here, so that
     * case answers {@code null} (a raw, undeclared reach can bind nothing further). A further subclass
     * declaring no type argument of its own (e.g. {@code class A extends Tags}) still resolves to {@code
     * Tags}'s own binding, because the walk continues past a non-parameterized level to that level's own
     * raw superclass.
     *
     * @param raw the {@code Map}-like type's own erased class
     * @return the overlay source {@link AnnotatedType}, or {@code null} when none is found up the chain
     */
    static AnnotatedType mapValueSlotOfClass(Class<?> raw) {
        if (raw == null) {
            return null;
        }
        AnnotatedType supertype = mapAssignableSupertype(raw);
        if (supertype == null) {
            return null;
        }
        AnnotatedType slot = mapValueSlot(supertype);
        return ownTypeParameterIndex(slot, raw) < 0 ? slot : null;
    }

    /**
     * The one annotated supertype of {@code raw} that is {@code Map}-assignable: {@code
     * raw.getAnnotatedSuperclass()} when its own erased class is {@code Map}-assignable, else the first
     * matching entry of {@code raw.getAnnotatedInterfaces()} — {@code null} when neither carries one.
     */
    private static AnnotatedType mapAssignableSupertype(Class<?> raw) {
        AnnotatedType superclass = raw.getAnnotatedSuperclass();
        Class<?> superclassErasure = superclass == null ? null : erase(superclass);
        if (superclassErasure != null && Map.class.isAssignableFrom(superclassErasure)) {
            return superclass;
        }
        for (AnnotatedType candidate : raw.getAnnotatedInterfaces()) {
            Class<?> candidateErasure = erase(candidate);
            if (candidateErasure != null && Map.class.isAssignableFrom(candidateErasure)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * {@code candidate}'s own index among {@code raw}'s own {@link Class#getTypeParameters()}, when
     * {@code candidate} is an {@link AnnotatedTypeVariable} wrapping a {@link
     * java.lang.reflect.TypeVariable} that {@code raw} itself declares — {@code -1} otherwise (a concrete,
     * already-resolved slot, or a type variable declared by a different class).
     */
    private static int ownTypeParameterIndex(AnnotatedType candidate, Class<?> raw) {
        if (!(candidate instanceof AnnotatedTypeVariable annotatedTypeVariable)
                || !(annotatedTypeVariable.getType() instanceof java.lang.reflect.TypeVariable<?> variable)
                || variable.getGenericDeclaration() != raw) {
            return -1;
        }
        java.lang.reflect.TypeVariable<?>[] parameters = raw.getTypeParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] == variable) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code type}'s own erased {@link Class}: {@code type}'s own {@link Class} directly, or the raw type
     * of {@code type}'s own {@link java.lang.reflect.ParameterizedType} — {@code null} for any other
     * {@link java.lang.reflect.Type} kind (a type variable, wildcard, or array), which never occurs for a
     * {@code Map<K,V>}-or-subclass declaration site or supertype-chain entry.
     */
    private static Class<?> erase(AnnotatedType type) {
        java.lang.reflect.Type raw = type.getType();
        if (raw instanceof Class<?> clazz) {
            return clazz;
        }
        if (raw instanceof java.lang.reflect.ParameterizedType parameterizedType
                && parameterizedType.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
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
     * (field, getter, creator parameter, any-setter, or a {@code Map} subclass's own supertype chain —
     * {@code null} otherwise), and the position's own member name, carried for a future diagnostic (not
     * read by this task's own pipeline steps).
     *
     * @param valueType     the value's declared {@link JavaType}, or {@code null} when the position has
     *                      none (an undeclared map content type)
     * @param annotatedType the position's own {@link AnnotatedType}, or {@code null}
     * @param memberName    the position's own member name, for a future diagnostic, or {@code null}
     */
    record ValuePosition(JavaType valueType, AnnotatedType annotatedType, String memberName) {}
}
