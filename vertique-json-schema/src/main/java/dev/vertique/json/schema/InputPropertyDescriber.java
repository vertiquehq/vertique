// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.ResolvedTypeWithMembers;
import com.fasterxml.classmate.TypeResolver;
import com.fasterxml.classmate.members.ResolvedField;
import com.fasterxml.classmate.members.ResolvedMethod;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.deser.AbstractDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.BuilderBasedDeserializer;
import com.fasterxml.jackson.databind.deser.CreatorProperty;
import com.fasterxml.jackson.databind.deser.DefaultDeserializationContext;
import com.fasterxml.jackson.databind.deser.SettableAnyProperty;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.deser.impl.TypeWrappedDeserializer;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.deser.std.MapDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDelegatingDeserializer;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedConstructor;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.AnnotatedWithParams;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.util.NameTransformer;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.MethodScope;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.TypeContext;
import com.github.victools.jsonschema.generator.impl.AttributeCollector;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.System.Logger.Level;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Describes an input object from the profile mapper's <em>resolved deserializer</em>, and hands the
 * schema library everything else.
 *
 * <p>The property set, its names, the creator (property-based, delegating, or scalar), the any-setter
 * — on the type, on its builder, or on a creator parameter — the unwrapped members and the routing
 * of every alias spelling are read from the {@code BeanDeserializer} Jackson resolves for the type,
 * so the document publishes exactly the names the binder reads. Each value schema and each
 * member-level constraint still comes from the schema library: a bound field or getter is handed to it
 * as the library's own member scope, so the Jackson, Jakarta Validation and Swagger modules apply
 * unchanged. Two member kinds have no library scope — a creator parameter and a setter — and their
 * constraints are translated by hand from Jackson's merged annotation map, which already carries the
 * same-named field's and getter's annotations. A builder method carries no constraint of its own: its
 * constraints are borrowed from the built type's Jackson-introspected property of the same wire name,
 * on the assumption — guaranteed by construction for a Lombok {@code @Builder}, not provable in
 * general — that the method sets that property. When that property has a getter and a backing field,
 * the borrow is published unconditionally, for any builder — exactly what {@code main}'s field walk
 * always did, since a getter-backed field's constraints were always published regardless of what any
 * builder method's body did with the value before assigning it. Only when the property has no getter
 * (the private, getter-less case {@code main}'s field walk never published either) does {@link
 * BuilderBorrowDetector} bound the borrow to the shape that assumption actually holds for (the exact
 * shape {@code @Builder @Jacksonized} generates, read through {@code java.lang.reflect} over Jackson's
 * own runtime-visible annotations), and every other builder's getter-less property is published by
 * type only — see the module's packaged {@code module.md} for the ruling and the documented
 * consequence for a hand-written builder that goes unresolved.
 *
 * <p>Two mechanisms are detected and refused rather than described, because a document describing
 * them would be false: a <em>bean-like</em> type — one whose own class carries an explicit type-level
 * {@code @JsonDeserialize(using = ...)} (or equivalent), <strong>or</strong> one the mapper's own
 * reflective introspection still reports properties for even though a profile module attached its
 * deserializer some other way ({@code SimpleModule.addDeserializer}, a {@code
 * BeanDeserializerModifier} wrapper) — unless the profile declares a schema override for it; and a
 * type bound case-insensitively through the mapper, a class-level or a member-level {@code
 * @JsonFormat}. Both fail generation with a bounded diagnostic naming the type and the remedy. A type
 * whose deserializer is not a bean deserializer for any <em>other</em> reason — introspection reports
 * no properties for it either, so it is a foreign, opaque type that never had bean properties to begin
 * with (a scalar, container, node, or Vert.x-style wrapper such as {@code JsonObject}/{@code
 * JsonArray}/{@code Buffer}) — is described as accepting any JSON value instead, since there is no
 * field walk such a refusal could be protecting for that type.
 *
 * <p>Registered after the annotation modules on purpose: the Jackson module's subtype resolver keeps
 * precedence for a {@code @JsonTypeInfo} root and consults this provider for each concrete subtype;
 * the profile override provider, registered before the modules, keeps precedence for an overridden
 * class, which is why an overridden custom-deserialized type is never refused here.
 */
final class InputPropertyDescriber implements CustomDefinitionProviderV2 {

    /** Resolves a Jackson-resolved type into the schema library's type model. */
    private static final TypeResolver CLASSMATE = new TypeResolver();

    /**
     * JDK {@code System.Logger} rather than SLF4J: this module's own architecture rule (FR-JSON-070)
     * freezes its compile dependencies to victools, Jackson, Jakarta Validation/Swagger annotations, and
     * {@code vertique-core}, with no logging facade among them ({@link MetadataConstraintSource}'s own
     * {@code LOG} field is the existing precedent this task follows for the same facility — rest-023 T003,
     * {@code D001}, owner decision Q1).
     */
    private static final System.Logger LOG = System.getLogger(InputPropertyDescriber.class.getName());

    private final ObjectMapper mapper;
    private final boolean strictSpellings;

    /**
     * The optional Bean Validation metadata supplement, consulted <em>on top of</em> the always-active
     * floor — the schema library's own Jakarta Validation module for a scoped field or getter, {@link
     * WalkConstraintSource} for a creator parameter, setter, or builder method — never in its place.
     * {@code null} when no {@link jakarta.validation.Validator} was supplied to the generator, in which
     * case the floor alone drives generation, unchanged from before this abstraction existed.
     */
    private final ConstraintSource supplement;

    /**
     * The validated, direction-filtered profile view this generator was built for, consulted only by
     * the member-level case-insensitive inline path (F4, security review round 1): that path builds a
     * schema by hand instead of asking Victools for the member's type, so it must consult the same
     * profile override a normal type-level lookup would have reached through {@link
     * ProfileOverrideDefinitionProvider}. {@code null} only for the legacy two-argument constructor,
     * which no production call site uses.
     */
    private final ValidatedProfile validatedProfile;

    /**
     * The shared value-position renderer (rest-023 T001; architecture round-2 condition C6) that
     * {@link #describeMapLike} and {@link #describeExtras} route their own value-schema rendering
     * through — see {@link ValuePositionRenderer}'s own class Javadoc for the pipeline this task wires.
     */
    private final ValuePositionRenderer valuePositionRenderer;

    /** The introspected ignored names per type, the one fact the deserializer does not carry. */
    private final Map<JavaType, Set<String>> ignoredNamesByType = new ConcurrentHashMap<>();

    /** The builders Jackson used, captured on a copy of the mapper; keyed by the type they built for. */
    private final Map<JavaType, BeanDeserializerBuilder> builders = new ConcurrentHashMap<>();

    private volatile ObjectMapper capturing;

    /** The types being described on the current generation path, to bound recursion. */
    private final Set<JavaType> inProgress = new HashSet<>();

    /**
     * The bean value types currently being populated <em>inline</em> at a value position — D004's own
     * conjunction inline rule (C5) and D005's own member-level inline path (S1) both register here for
     * the duration of their own {@link #populateObjectSchema} call, distinct from {@link #inProgress}
     * (security round-3 MEDIUM; {@code decisions/D005-…md} § Recursion bound). {@link #inlineBeanSchema}
     * refuses re-entry when a type is already in this set <em>or</em> in {@link #inProgress}; {@link
     * #provideCustomSchemaDefinition} itself refuses — never silently falls back to a standard,
     * reflection-built {@code $defs} entry via its own pre-existing "return {@code null} on re-entry"
     * branch — for a type already in this set, closing the round-3 MEDIUM's own silent-misdescription
     * class.
     */
    private final Set<JavaType> inlineInProgress = new HashSet<>();

    /**
     * @param mapper          the profile's mapper, which the binder parses a body with
     * @param strictSpellings whether the profile forbids several spellings of one property
     */
    InputPropertyDescriber(ObjectMapper mapper, boolean strictSpellings) {
        this(mapper, strictSpellings, null, null);
    }

    /**
     * @param mapper           the profile's mapper, which the binder parses a body with
     * @param strictSpellings  whether the profile forbids several spellings of one property
     * @param supplement       the Bean Validation metadata supplement consulted on top of the
     *                         always-active floor, or {@code null} when the generator was built
     *                         without a {@link jakarta.validation.Validator}
     * @param validatedProfile the validated, direction-filtered profile view, consulted by the
     *                         member-level case-insensitive inline path so a declared override still
     *                         applies there; {@code null} is treated as "no overrides declared"
     */
    InputPropertyDescriber(
            ObjectMapper mapper,
            boolean strictSpellings,
            ConstraintSource supplement,
            ValidatedProfile validatedProfile) {
        this.mapper = mapper;
        this.strictSpellings = strictSpellings;
        this.supplement = supplement;
        this.validatedProfile = validatedProfile;
        this.valuePositionRenderer = new ValuePositionRenderer(validatedProfile, supplement, this::inlineBeanSchema);
    }

    /** Clears the per-generation recursion state after an abnormal exit. */
    void resetAfterAbortedGeneration() {
        inProgress.clear();
        inlineInProgress.clear();
    }

    @Override
    public CustomDefinition provideCustomSchemaDefinition(ResolvedType resolved, SchemaGenerationContext context) {
        if (resolved == null) {
            return null;
        }
        Class<?> erased = resolved.getErasedType();
        if (erased == java.util.OptionalInt.class
                || erased == java.util.OptionalLong.class
                || erased == java.util.OptionalDouble.class) {
            return primitiveOptional(erased, context);
        }
        // rest-023 T003 (D001): a map-like position — java.util.Map, its JDK implementations, or a
        // user-defined subclass — is carved out of the java.*/javax.*/jakarta.*/com.fasterxml.jackson.*
        // exclusion below, ahead of it, so it still reaches describe() -> describeMapLike and is
        // described with V's own schema as additionalProperties, at every reach (property, parameter,
        // extras value, collection item, or nested map). Object/JsonNode/TreeNode are not Map-assignable,
        // so they are unaffected and keep flowing through the ordinary opaque-type paths below.
        boolean mapLike = Map.class.isAssignableFrom(erased);
        if (!mapLike
                && (erased.isPrimitive()
                        || erased.isArray()
                        || erased.isEnum()
                        || erased.isAnnotation()
                        || erased.getName().startsWith("java.")
                        || erased.getName().startsWith("javax.")
                        || erased.getName().startsWith("jakarta.")
                        || erased.getName().startsWith("com.fasterxml.jackson."))) {
            return null;
        }
        JavaType javaType = toJavaType(resolved);
        if (!mapLike && (javaType.isEnumType() || javaType.isReferenceType() || javaType.isCollectionLikeType())) {
            return null;
        }
        // D004/D005 § Recursion bound (security round-3 MEDIUM): a type already being populated
        // inline at a value position is refused here, before the ordinary inProgress guard below ever
        // runs — otherwise a non-inline re-entry of an inline-registered type (a plain $ref reached
        // from elsewhere in the same document while the inline population is still on the stack) would
        // fall through to the inProgress guard's own "return null on re-entry" branch, and the library
        // would silently hand back its own standard, reflection-built $defs entry instead of the
        // bounded diagnostic this class relies on everywhere else.
        if (inlineInProgress.contains(javaType)) {
            throw Diagnostics.failure(
                    Diagnostics.typeIdentity(javaType.getRawClass())
                            + " is referenced by $ref while being described inline; declare a"
                            + " JsonSchemaTypeOverride or close it at the type",
                    null);
        }
        if (!inProgress.add(javaType)) {
            // A self-reference inside the definition being built: the library resolves it as a
            // reference to the definition once this provider returns.
            return null;
        }
        try {
            return describe(javaType, resolved, context);
        } finally {
            inProgress.remove(javaType);
        }
    }

    private CustomDefinition describe(JavaType javaType, ResolvedType resolved, SchemaGenerationContext context) {
        // W2 (spike/deserializer-driven-schema round 4 ruling): unwrapped through getDelegatee() before
        // this method decides whether the type's deserializer was replaced — mirroring the existing
        // TypeWrappedDeserializer unwrap rootDeserializer itself performs. A mapper-wide
        // BeanDeserializerModifier that wraps every bean deserializer in a forwarding
        // DelegatingDeserializer subclass still ends up calling the wrapped bean deserializer at bind
        // time through getDelegatee(), so the wrapped bean is described from its own delegate rather
        // than misclassified as an opaque type-level override the way F1's own refusal treats a genuine
        // custom deserializer. Bounded to a pure forwarder (W-1, round 5 review finding): a
        // DelegatingDeserializer subclass that overrides deserialize(...)/deserializeWithType(...) itself
        // is left un-unwrapped by unwrapDelegating and falls through to the same bean-like refusal below,
        // since such an override may read a wire shape the delegate's own bean description does not
        // capture — see unwrapDelegating's own Javadoc for the exact bound.
        JsonDeserializer<?> deserializer = unwrapDelegating(rootDeserializer(javaType));
        if (deserializer == null || deserializer instanceof AbstractDeserializer) {
            // A polymorphic base: the Jackson module's subtype resolver owns it.
            return null;
        }
        if (deserializer instanceof MapDeserializer) {
            return describeMapLike(javaType, context);
        }
        if (!(deserializer instanceof BeanDeserializerBase bean)) {
            // F1 (security review round 1, HIGH): declaresOwnDeserializerOverride alone only catches a
            // class-level @JsonDeserialize(using = ...); a deserializer a profile module registers
            // (SimpleModule.addDeserializer, or a BeanDeserializerModifier wrapper) carries no such
            // annotation, so an application DTO the mapper's own reflective introspection still reports
            // a settable bean property for — a bean-like type main's field walk would have published —
            // must be refused too, or a document describing it as {} silently drops every constraint on
            // it. "Settable" (BeanPropertyDefinition#couldDeserialize(): a field, a setter, a creator
            // parameter, or a mutable-collection getter Jackson would fill in place) rather than merely
            // "known" matters: Vert.x's own Buffer carries a no-argument getBytes() getter, which
            // Jackson's introspection reports as a property regardless — findProperties().isEmpty()
            // alone would misclassify it as bean-like and wrongly refuse it, exactly the false positive
            // D005 calls out by name. Vert.x's io.vertx.* family (JsonObject, JsonArray, Buffer, ...) is
            // additionally excluded outright: JsonObject#getMap() and JsonArray#getList() are themselves
            // mutable-collection getters Jackson's own "fill in place" fallback treats as settable, so
            // couldDeserialize() alone is not a safe signal for this specific, well-known opaque-wrapper
            // family either — the same family this method's own class Javadoc and D005 name by example.
            // A type with neither a
            // settable property nor an io.vertx.* package (a scalar, a container, a node) never had a
            // field walk to protect and stays described as unconstrained. S5 (spike/deserializer-driven
            // -schema round 4 ruling): this decision is the shared BeanLikeTypes.beanLike check, the one
            // exclusion list also consulted by ValidatedProfile's own F6 override-closure check.
            boolean beanLike = BeanLikeTypes.beanLike(mapper, javaType.getRawClass());
            if (!declaresOwnDeserializerOverride(javaType) && !beanLike) {
                // A scalar, container, node, or Vert.x-style opaque wrapper: some module registered a
                // plain (non-bean) deserializer for this *foreign* type, but the type's own class
                // carries no explicit @JsonDeserialize(using = ...) and introspection reports no
                // properties for it — it never looked like a bean to begin with, so there is no field
                // walk this description could be dropping. Describing it as accepting any JSON value
                // (exactly like Object.class/JsonNode.class) is honest: it never rejects traffic the
                // binder would accept, unlike refusing generation outright.
                return unconstrained(context);
            }
            throw refuseCustomDeserializer(javaType, deserializer);
        }
        ObjectNode definition = context.getGeneratorConfig().createObjectNode();
        ValueInstantiator instantiator = bean.getValueInstantiator();
        requireNotDelegating(javaType, instantiator);
        String scalar = scalarCreator(instantiator);
        if (scalar != null && !instantiator.canCreateFromObjectWith() && !instantiator.canCreateUsingDefault()) {
            definition.put("type", scalar);
            return new CustomDefinition(
                    definition, CustomDefinition.DefinitionType.STANDARD, CustomDefinition.AttributeInclusion.NO);
        }

        boolean caseInsensitive = bean.isCaseInsensitive();
        populateObjectSchema(
                definition, javaType, resolved, bean, builderFor(javaType, bean), context, caseInsensitive, false);
        return new CustomDefinition(
                definition, CustomDefinition.DefinitionType.STANDARD, CustomDefinition.AttributeInclusion.YES);
    }

    /**
     * Whether {@code javaType}'s own class carries an explicit type-level deserializer override —
     * {@code @JsonDeserialize(using = ...)}, or the equivalent read through a mix-in or module — as
     * opposed to a plain {@link JsonDeserializer} some module registered for a foreign type that never
     * looked like a bean in the first place.
     *
     * <p>This is the line between the two non-bean-deserializer cases {@link #describe} must tell
     * apart: a type whose own author swapped its deserializer, which may be hiding real structure a
     * field walk would otherwise have described (refused), and an opaque wrapper — a scalar,
     * container, node, or Vert.x-style type such as {@code JsonObject}/{@code JsonArray}/{@code
     * Buffer} — that a module deserializes directly and which never had bean properties to begin with
     * (described as unconstrained).
     *
     * @param javaType the type being described
     * @return {@code true} when the class itself declares its own deserializer
     */
    private boolean declaresOwnDeserializerOverride(JavaType javaType) {
        AnnotatedClass classInfo = introspection(javaType).getClassInfo();
        return introspector().findDeserializer(classInfo) != null;
    }

    /**
     * The shared diagnostic for a delegating creator (W1: both the object- and the array-delegating
     * shape) — neither reads a named property of its own, so a document describing either delegate's
     * shape honestly would open the boundary to keys {@code main} never accepted and leave any
     * constraint on this type's own fields dead on input.
     *
     * @param javaType the type being described
     * @param kind     the delegating-creator kind, named in the message ("a delegating @JsonCreator" or
     *                 "an array-delegating @JsonCreator")
     * @return the bounded diagnostic to throw
     */
    private static JsonSchemaGenerationException refuseDelegatingCreator(JavaType javaType, String kind) {
        return Diagnostics.failure(
                "JSON Schema generation failed for " + Diagnostics.typeIdentity(javaType.getRawClass())
                        + ": the type binds through " + kind + ", which reads no named property of its"
                        + " own — its wire shape is whatever the delegate type's deserializer accepts,"
                        + " which a schema's properties cannot describe; declare a JsonSchemaTypeOverride"
                        + " for the type on the profile, or bind it through a property-based creator",
                null);
    }

    /**
     * Refuses a delegating or array-delegating creator (W1), shared between the root/nested-reference
     * path in {@link #describe} and the member-level case-insensitive inline path in {@link
     * #propertySchema} (F4, security review round 1): the inline path builds its schema by hand
     * instead of asking Victools for the member's type, so before this fix it never ran this check at
     * all, silently describing a delegating creator's own fields instead of refusing generation.
     *
     * @param javaType     the type being described
     * @param instantiator the type's value instantiator
     * @throws JsonSchemaGenerationException when the type binds through a delegating or
     *     array-delegating {@code @JsonCreator}
     */
    private static void requireNotDelegating(JavaType javaType, ValueInstantiator instantiator) {
        if (instantiator.canCreateUsingDelegate()) {
            // Treated exactly like a type-level custom deserializer: the whole object is bound
            // through a delegate type, so no named property is ever read from this type's own wire
            // shape, and a document describing the delegate's shape honestly would open the boundary to
            // keys main never accepted and leave any constraint on this type's own fields dead on input.
            throw refuseDelegatingCreator(javaType, "a delegating @JsonCreator");
        }
        if (instantiator.canCreateUsingArrayDelegate()) {
            // Same shape, same remedy, an array-shaped delegate instead of an object-shaped one (W1): a
            // single-argument delegating creator whose declared parameter type is array-like (a
            // Collection or an array) reads no named property of its own either — its wire shape is a
            // JSON array, which a schema's properties cannot describe any more than the object-delegate
            // case above could.
            throw refuseDelegatingCreator(javaType, "an array-delegating @JsonCreator");
        }
    }

    /**
     * The shared diagnostic for a deserializer that does not resolve to a bean deserializer — a
     * type-level custom deserializer refused at the root ({@link #describe}), or an {@code
     * @JsonUnwrapped} child whose own {@code unwrappingDeserializer(...)} resolved to a genuinely
     * different, non-bean instance (the unwrapped-child loop in {@link #populateObjectSchema}, C-1):
     * both name a type whose wire shape a schema cannot describe, refused with the same remedy rather
     * than silently skipped.
     *
     * @param javaType     the type being described
     * @param deserializer the deserializer that failed to resolve to a bean deserializer, or {@code
     *                     null} when none resolved at all
     * @return the bounded diagnostic to throw
     */
    private static JsonSchemaGenerationException refuseCustomDeserializer(
            JavaType javaType, JsonDeserializer<?> deserializer) {
        return refuseCustomDeserializer(javaType, deserializer, null);
    }

    /**
     * The unwrapped-child variant of {@link #refuseCustomDeserializer(JavaType, JsonDeserializer)}
     * (C-1): names the unwrapped member alongside its type, the same posture {@link
     * #requireCaseSensitive} already takes for a case-insensitive unwrapped member, so a caller with
     * several unwrapped members on the same parent can tell which one was refused.
     *
     * @param javaType     the unwrapped member's own type
     * @param deserializer the deserializer that failed to resolve to a bean deserializer, or {@code
     *                     null} when none resolved at all
     * @param memberName   the parent's own member name carrying this unwrapped child
     * @return the bounded diagnostic to throw
     */
    private static JsonSchemaGenerationException refuseCustomDeserializer(
            JavaType javaType, JsonDeserializer<?> deserializer, String memberName) {
        String identity = deserializer == null
                ? "no deserializer"
                : Diagnostics.truncate(deserializer.getClass().getName(), Diagnostics.MAX_SHORT_IDENTITY_LENGTH);
        String subject = memberName == null
                ? Diagnostics.typeIdentity(javaType.getRawClass())
                : Diagnostics.typeIdentity(javaType.getRawClass()) + " (the unwrapped member \""
                        + Diagnostics.truncate(memberName, Diagnostics.MAX_SHORT_IDENTITY_LENGTH) + "\")";
        return Diagnostics.failure(
                "JSON Schema generation failed for " + subject
                        + ": the profile's mapper deserializes it with " + identity
                        + ", whose wire shape the generator cannot describe; declare a JsonSchemaTypeOverride for"
                        + " the type on the profile, or deserialize it as a bean",
                null);
    }

    /** A schema accepting any JSON value, for an opaque type this describer cannot know the shape of. */
    private static CustomDefinition unconstrained(SchemaGenerationContext context) {
        ObjectNode definition = context.getGeneratorConfig().createObjectNode();
        return new CustomDefinition(
                definition, CustomDefinition.DefinitionType.STANDARD, CustomDefinition.AttributeInclusion.NO);
    }

    /**
     * Fills in an object schema's {@code properties}, {@code patternProperties}, {@code required},
     * alias plan, extras and reserved names from a bean deserializer's bound properties.
     *
     * <p>Shared between the root type — described once per generation into the definition Victools
     * asked for — and a nested member whose own type is bound case-insensitively only through that
     * member's contextual {@code @JsonFormat(with = ACCEPT_CASE_INSENSITIVE_PROPERTIES)}: such a member
     * cannot share the type's ordinary (case-sensitive) definition, since the same class used elsewhere
     * without the annotation stays case-sensitive there, so it is described inline instead. See
     * {@link #propertySchema}.
     *
     * @param definition      the object node to fill in; already carries no keyword this method writes
     * @param javaType        the type being described
     * @param resolved        the schema library's resolved type for {@code javaType}
     * @param bean            the type's resolved bean deserializer
     * @param builder         the captured builder the deserializer was assembled from, or {@code null}
     * @param context          the active generation context
     * @param caseInsensitive  whether {@code bean} binds its properties case-insensitively
     * @param extrasSuppressed whether the type's own any-setter extras are suppressed (D005, T005): when
     *                         {@code true}, {@link #describeExtras} is never consulted and {@code
     *                         additionalProperties: false} is written directly instead — the member-level
     *                         inline-closure rule's own effect, reused by {@link #inlineMemberSchema} for
     *                         both the CI-plus-{@code FALSE} composition and the plain {@code FALSE}
     *                         case; every other caller passes {@code false}, unchanged from before this
     *                         parameter existed
     */
    /**
     * One {@code @JsonUnwrapped} sibling's own resolution, captured up front (C1, spike round 4
     * CRITICAL) so whether extras will be described can be computed over every sibling before any one
     * of them is processed — see the C1 comment in {@link #populateObjectSchema}.
     *
     * @param memberName    the parent's own member name carrying this unwrapped child, named in a
     *                      nested-unwrap diagnostic
     * @param transformer   the unwrapping name transformer Jackson resolved for this child
     * @param childClass    the unwrapped child's own raw type
     * @param childBuilder  the child's own captured builder, or {@code null} when it declares no
     *                      builder-visible property at all
     * @param childResolved the schema library's resolved type for the child
     * @param childBound    the child's own bound properties, read from the transformed deserializer
     */
    private record UnwrappedChild(
            String memberName,
            NameTransformer transformer,
            Class<?> childClass,
            BeanDeserializerBuilder childBuilder,
            ResolvedType childResolved,
            List<SettableBeanProperty> childBound) {}

    /**
     * Whether a type built from {@code builder} would itself describe extras on its own object schema:
     * its own any-setter, or one declared by a {@code @JsonUnwrapped} member of its own (T005 round 2,
     * MEDIUM). The one place this "would this type describe extras?" question is answered, so {@link
     * #populateObjectSchema}'s own {@code extrasWillBeDescribed} computation and {@link #propertySchema}'s
     * member-level {@code additionalProperties = FALSE} trigger never diverge — before this helper, the
     * member-level trigger read only the value type's own builder any-setter and silently fell through to
     * the open shared {@code $ref} when the any-setter was reachable only through the value type's own
     * unwrapped child. Stops at one level of unwrapping, the same bound {@link #requireNoNestedUnwrapping}
     * enforces once extras actually need describing — a grandchild's own any-setter is not consulted.
     *
     * @param builder the type's own captured builder, or {@code null} when it declares no builder-visible
     *                property at all (never describes extras)
     */
    private boolean wouldDescribeExtras(BeanDeserializerBuilder builder) {
        if (builder == null) {
            return false;
        }
        if (builder.getAnySetter() != null) {
            return true;
        }
        Iterator<SettableBeanProperty> it = builder.getProperties();
        while (it.hasNext()) {
            SettableBeanProperty property = it.next();
            NameTransformer transformer = introspector().findUnwrappingNameTransformer(property.getMember());
            if (transformer == null) {
                continue;
            }
            JsonDeserializer<?> child = unwrapDelegating(rootDeserializer(property.getType()));
            JsonDeserializer<?> renamed = child == null ? null : child.unwrappingDeserializer(transformer);
            if (renamed == child || !(renamed instanceof BeanDeserializerBase unwrapped)) {
                // Declined to unwrap, or a shape-changing custom deserializer: neither describes extras
                // through this member. A genuinely refused custom deserializer surfaces its own bounded
                // diagnostic later, when the type is actually described (populateObjectSchema's own
                // unwrapped-child loop), not from this best-effort probe.
                continue;
            }
            BeanDeserializerBuilder childBuilder = builderFor(property.getType(), unwrapped);
            if (childBuilder != null && childBuilder.getAnySetter() != null) {
                return true;
            }
        }
        return false;
    }

    private void populateObjectSchema(
            ObjectNode definition,
            JavaType javaType,
            ResolvedType resolved,
            BeanDeserializerBase bean,
            BeanDeserializerBuilder builder,
            SchemaGenerationContext context,
            boolean caseInsensitive,
            boolean extrasSuppressed) {
        definition.put("type", "object");
        ObjectNode properties = definition.putObject("properties");
        List<String> required = new ArrayList<>();
        Class<?> builtClass = javaType.getRawClass();
        Set<String> published = new LinkedHashSet<>();
        Set<String> excludedFromReservation = new LinkedHashSet<>();
        List<SettableBeanProperty> bound = boundProperties(bean, builder);
        SettableAnyProperty anySetter = builder == null ? null : builder.getAnySetter();
        Set<Object> storage = storageMembers(javaType, anySetter);
        // D004: every any-setter feeding the shared extras position, parent first — the parent's own,
        // when it declares one, then each unwrapped sibling's own, in declaration order (below). One
        // entry behaves byte-identically to the pre-T004 single-slot rule; more than one is described
        // as their conjunction (describeExtras, ValuePositionRenderer#renderConjunction).
        List<SettableAnyProperty> anySetters = new ArrayList<>();
        if (anySetter != null) {
            anySetters.add(anySetter);
        }
        ObjectNode[] patternProperties = new ObjectNode[1];
        // F2 (security review round 1, HIGH): every unwrapped child's alias spellings and hidden/ignored
        // names, folded into the parent's own alias plan and reserved-name seed below, keyed by the
        // child's wire names with any unwrapped prefix or suffix applied — see the unwrapped loop.
        Map<String, List<String>> unwrappedAliasPlan = new TreeMap<>();
        Set<String> unwrappedReservedSeed = new LinkedHashSet<>();
        for (SettableBeanProperty property : bound) {
            String name = property.getName();
            if (name.isEmpty() || published.contains(name)) {
                continue;
            }
            if (isStorage(storage, property.getMember())) {
                continue; // an any-accessor's storage: reserved below, never published
            }
            if (isUnjoinableConstraintFreeCreatorParameter(property, builtClass)) {
                // Renamed away from every member, with no constraint of its own: publishing it bare
                // would invent a property main never had, and it must not be reserved either, since
                // reserving it would reject the type's valid traffic that main's field walk accepted
                // through no named property at all (design proof CR1c).
                excludedFromReservation.add(name);
                continue;
            }
            JsonNode schema = propertySchema(property, builtClass, resolved, context, required);
            if (schema == null) {
                continue; // hidden on purpose: reserved below, never published
            }
            properties.set(name, schema);
            published.add(name);
            if (caseInsensitive) {
                publishFolded(definition, patternProperties, name, schema, builtClass);
            }
        }

        if (builder != null) {
            List<UnwrappedChild> unwrappedChildren = new ArrayList<>();
            Iterator<SettableBeanProperty> it = builder.getProperties();
            while (it.hasNext()) {
                SettableBeanProperty property = it.next();
                NameTransformer transformer = introspector().findUnwrappingNameTransformer(property.getMember());
                if (transformer == null) {
                    continue;
                }
                // C-1 (security review round 6 finding): routed through unwrapDelegating, the same bound
                // applied at the root (describe()) and inline (propertySchema()) seams — otherwise a
                // mapper-wide BeanDeserializerModifier that wraps every bean deserializer in a forwarding
                // DelegatingDeserializer subclass leaves this child's own root deserializer un-unwrapped,
                // the instanceof BeanDeserializerBase check below fails, and the child is silently
                // skipped: neither its property nor its constraint reaches the document, and (for a
                // case-insensitively bound child) requireCaseSensitive below never even runs.
                JsonDeserializer<?> child = unwrapDelegating(rootDeserializer(property.getType()));
                JsonDeserializer<?> renamed = child == null ? null : child.unwrappingDeserializer(transformer);
                if (renamed == child) {
                    // C-1 (security review round 7 finding): Jackson's own bound —
                    // unwrappingDeserializer(...) returning the very same instance back is its documented
                    // signal that the deserializer does not support unwrapping at all (MapDeserializer, the
                    // abstract-type-id-resolving deserializer, the untyped-Object/JsonNode deserializers,
                    // OptionalDeserializer, ...), not a refusal. BeanDeserializerBase.resolve() then leaves
                    // the member bound as an ordinary nested property, which the describer's own first
                    // (non-unwrapped) property loop above already publishes under this member's own name;
                    // refusing it here would reject a DTO shape the binder accepts.
                    continue;
                }
                if (!(renamed instanceof BeanDeserializerBase unwrapped)) {
                    // A genuinely different, non-bean instance: unwrappingDeserializer(...) changed the
                    // wire shape (a real custom deserializer), not merely declined to unwrap. Refuse with
                    // the same bounded custom-deserializer diagnostic the root seam throws, naming this
                    // member so a parent with several unwrapped members can tell which one was refused.
                    throw refuseCustomDeserializer(property.getType(), renamed, property.getName());
                }
                Class<?> childClass = property.getType().getRawClass();
                requireCaseSensitive(unwrapped, childClass, "the unwrapped member " + property.getName());
                BeanDeserializerBuilder childBuilder = builderFor(property.getType(), unwrapped);
                ResolvedType childResolved = resolve(context, property.getType());
                List<SettableBeanProperty> childBound = boundProperties(unwrapped, null);
                unwrappedChildren.add(new UnwrappedChild(
                        property.getName(), transformer, childClass, childBuilder, childResolved, childBound));
            }

            // C1 (spike/deserializer-driven-schema round 4, CRITICAL): whether extras will be described
            // is computed once, over the parent's own any-setter and EVERY unwrapped sibling's own
            // builder, before any sibling is processed — not incrementally discovered while looping
            // sibling by sibling. Before this fix, a sibling processed before the one that actually
            // declares @JsonAnySetter saw the loop's own "so far" signal still false and skipped its own
            // fold entirely: {@code Parent { @JsonUnwrapped A a; @JsonUnwrapped B b }} with the
            // any-setter on B alone still left A's own alias and hidden member unfolded when A was
            // processed first, regardless of the fact that the type as a whole is any-setter-shaped.
            // T005 round 2: computed through the shared #wouldDescribeExtras(BeanDeserializerBuilder) helper
            // so this "own or unwrapped sibling any-setter" question never diverges from propertySchema's
            // own member-level FALSE trigger, and gated by extrasSuppressed so a member-level FALSE that
            // closes this very type also suppresses the conjunction path here — no any-setter, own or
            // unwrapped, is described once the type's own extras are already closed.
            boolean extrasWillBeDescribed = !extrasSuppressed && wouldDescribeExtras(builder);

            for (UnwrappedChild sibling : unwrappedChildren) {
                if (extrasWillBeDescribed) {
                    // F2 (security review round 1, HIGH): a nested @JsonUnwrapped chain (this unwrapped
                    // child itself declares another unwrapped member) is refused rather than folded
                    // unsoundly on an any-setter type — the same posture #{@link #requireCaseSensitive}
                    // already takes for a case-insensitive unwrapped member. Folding is bounded to one
                    // level: the reserved-name and alias-spelling sweep below reads the child's own
                    // introspection and bound-property list directly, which does not itself descend into
                    // a grandchild's own unwrapped member, so a grandchild's alias or hidden member could
                    // otherwise bind through the extras bucket unconstrained exactly like F2's own probe.
                    requireNoNestedUnwrapping(sibling.childBuilder(), sibling.childClass(), sibling.memberName());
                }
                for (SettableBeanProperty childProperty : sibling.childBound()) {
                    String name = childProperty.getName();
                    if (name.isEmpty() || published.contains(name)) {
                        continue;
                    }
                    JsonNode schema = propertySchema(
                            childProperty, sibling.childClass(), sibling.childResolved(), context, required);
                    if (schema != null) {
                        properties.set(name, schema);
                        published.add(name);
                        if (caseInsensitive) {
                            publishFolded(definition, patternProperties, name, schema, builtClass);
                        }
                    }
                }
                if (sibling.childBuilder() != null) {
                    // D004: collect this sibling's own any-setter too (not only the first found), so
                    // the shared key describes the conjunction of every any-setter in the set — the
                    // pre-T004 single-slot rule stopped at the first sibling with one.
                    SettableAnyProperty siblingAnySetter =
                            sibling.childBuilder().getAnySetter();
                    if (siblingAnySetter != null) {
                        anySetters.add(siblingAnySetter);
                    }
                }
                if (extrasWillBeDescribed) {
                    // Fold this child's own alias spellings and hidden/ignored names into the parent's
                    // plan and reservation, keyed by the child's wire names with the unwrapping prefix or
                    // suffix applied — see the F2 comment above and this method's own class Javadoc. Runs
                    // for every sibling once extrasWillBeDescribed is known, regardless of which sibling
                    // this one is processed relative to the one that actually declares the any-setter.
                    foldUnwrappedChildIntoParentPlan(
                            sibling.transformer(),
                            sibling.childClass(),
                            sibling.childBound(),
                            published,
                            unwrappedAliasPlan,
                            unwrappedReservedSeed);
                }
            }
        }

        if (properties.isEmpty()) {
            definition.remove("properties");
        }
        if (!required.isEmpty()) {
            ArrayNode node = definition.putArray("required");
            // Presence under another casing is not enforced: `required` names only the canonical
            // spelling, so a client sending the required value under a different casing that Jackson
            // still binds is rejected by the schema even though the binder would accept it (measured,
            // see the module report).
            new LinkedHashSet<>(required).forEach(node::add);
        }

        Map<String, List<String>> aliasPlan = aliasPlan(bean, bound, published);
        unwrappedAliasPlan.forEach((claimant, spellings) ->
                aliasPlan.computeIfAbsent(claimant, key -> new ArrayList<>()).addAll(spellings));
        if (!aliasPlan.isEmpty()) {
            if (caseInsensitive) {
                // The alias-expansion post-pass rewrites `required`/`enum` branches keyed on exact wire
                // spellings; folding those together with case-insensitive binding is not a bounded
                // extension of that mechanism, so the combination is refused rather than described
                // unsoundly.
                throw Diagnostics.failure(
                        "JSON Schema generation failed for " + Diagnostics.typeIdentity(builtClass)
                                + ": the type is bound case-insensitively and declares an alias spelling,"
                                + " which the generator cannot fold together; declare a"
                                + " JsonSchemaTypeOverride for the type on the profile, or bind it"
                                + " case-sensitively",
                        null);
            }
            ObjectNode marker = definition.putObject(AnnotationJsonSchemaGenerator.AliasExpansion.MARKER);
            marker.put(AnnotationJsonSchemaGenerator.AliasExpansion.STRICT, strictSpellings);
            ObjectNode byWireName = marker.putObject(AnnotationJsonSchemaGenerator.AliasExpansion.ALIASES);
            aliasPlan.forEach((wireName, spellings) -> {
                ArrayNode array = byWireName.putArray(wireName);
                spellings.forEach(array::add);
            });
        }

        // D005, T005: an extras-suppressed member-level inline description (a member-level FALSE, alone
        // or composed with case-insensitivity) never consults describeExtras at all — additionalProperties
        // is written as the literal false this hand-built inline node otherwise never receives, since it
        // bypasses the standard CustomDefinition/AttributeInclusion path the Swagger module's own
        // class-level FALSE handling relies on (describeExtras's own class-level check, just above).
        boolean extrasDescribed;
        if (extrasSuppressed) {
            definition.put("additionalProperties", false);
            extrasDescribed = false;
        } else {
            extrasDescribed = describeExtras(definition, anySetters, builtClass, context);
        }
        Set<String> reserved;
        if (extrasDescribed) {
            reserved = reservedNames(javaType, bean, bound, published, aliasPlan);
            reserved.removeAll(excludedFromReservation);
            // F2: every unwrapped child's own hidden/ignored name and bound-but-unpublished name, folded
            // in beside the parent's own reservation — see foldUnwrappedChildIntoParentPlan.
            reserved.addAll(unwrappedReservedSeed);
            reserved.removeAll(published);
        } else {
            reserved = Set.of();
        }
        if (caseInsensitive) {
            // F3 (security review round 1, HIGH): emitted unconditionally, not only where extras are
            // described (C1's own premise). Jackson folds a case-insensitively bound property name with
            // String#toLowerCase() (no explicit Locale), and a non-ASCII code point can fold to an ASCII
            // letter regardless of locale (U+212A KELVIN SIGN folds to ASCII 'k'). Such a key binds to
            // the real, constrained member at the binder. A *closed* CI type (no any-setter) at REST has
            // no other closure at all — no additionalProperties: false, since REST has no hardener — so
            // without this rule the key is simply accepted and bound, exactly the bypass C1 was measured
            // against; where extras are also described, the ASCII-only patternProperties fold and the
            // reserved-name pattern both miss it, so the key falls through to additionalProperties (the
            // extras bucket) instead of the member's own constraint. Refusing any key carrying a
            // non-ASCII code unit outright, always, closes both gaps the same way.
            definition.set("propertyNames", caseInsensitivePropertyNamesRule(reserved, builtClass));
        } else if (extrasDescribed && !reserved.isEmpty()) {
            ObjectNode rule = JsonNodeFactory.instance.objectNode();
            ArrayNode values = rule.putObject("not").putArray("enum");
            reserved.forEach(values::add);
            definition.set("propertyNames", rule);
        }
    }

    /**
     * The {@code propertyNames} rule for a case-insensitively bound type with extras described:
     * refuses a key matching one of {@code reserved}'s ASCII case folds (when any), and, unconditionally
     * (C1), a key containing any non-ASCII code unit — combined as two alternatives of one regex, since
     * a JSON Schema object carries at most one {@code not}. The reserved-name alternative keeps its own
     * anchors (an exact-name match); the non-ASCII alternative is deliberately unanchored, since it must
     * refuse a code unit occurring anywhere in the key, not only a key consisting of nothing else.
     *
     * @param reserved   the reserved names to fold-exclude, possibly empty
     * @param builtClass the type being described, for the diagnostic a reserved name's own fold may throw
     * @return the {@code propertyNames} rule
     */
    private static ObjectNode caseInsensitivePropertyNamesRule(Set<String> reserved, Class<?> builtClass) {
        String nonAscii = "[^\\x00-\\x7F]";
        String pattern =
                reserved.isEmpty() ? nonAscii : "(?:" + combinedFoldPattern(reserved, builtClass) + ")|" + nonAscii;
        ObjectNode rule = JsonNodeFactory.instance.objectNode();
        rule.putObject("not").put("pattern", pattern);
        return rule;
    }

    /** A primitive optional is bound by the Jdk8 module as the scalar or null; the library alone renders a bare object. */
    private static CustomDefinition primitiveOptional(Class<?> erased, SchemaGenerationContext context) {
        ObjectNode definition = context.getGeneratorConfig().createObjectNode();
        ArrayNode types = definition.putArray("type");
        types.add(erased == java.util.OptionalDouble.class ? "number" : "integer");
        types.add("null");
        return new CustomDefinition(
                definition, CustomDefinition.DefinitionType.STANDARD, CustomDefinition.AttributeInclusion.NO);
    }

    /** A map (or map subclass) is bound as a map: its fields are never filled, and its entries are its values. */
    private CustomDefinition describeMapLike(JavaType javaType, SchemaGenerationContext context) {
        ObjectNode definition = context.getGeneratorConfig().createObjectNode();
        definition.put("type", "object");
        JavaType content = javaType.getContentType();
        // rest-023 T003 (D001, S4): a describeMapLike caller has no member-position AnnotatedType at all
        // (a type-level reach from describe(), reached identically for every member referencing the
        // type) — its own overlay source, if any, is the type's own supertype chain (a Map subclass such
        // as `class Tags extends HashMap<String, @Size(max=3) String>`), which is intrinsic to the type
        // and therefore identical, and safe to share, at every position referencing it. A plain
        // java.util.Map (no user subclass) carries no such chain, so this resolves to null exactly as
        // before T003 for that shape — an open position (content == null, or one of the renderer's own
        // unconstrained value types) omits the additionalProperties keyword entirely, exactly as before
        // this extraction.
        AnnotatedType typeLevelOverlay = ValuePositionRenderer.mapValueSlotOfClass(javaType.getRawClass());
        JsonNode valueSchema = valuePositionRenderer.renderValueSchema(
                context, new ValuePositionRenderer.ValuePosition(content, typeLevelOverlay, null));
        if (valueSchema != null) {
            definition.set("additionalProperties", valueSchema);
        }
        return new CustomDefinition(
                definition, CustomDefinition.DefinitionType.STANDARD, CustomDefinition.AttributeInclusion.YES);
    }

    // ---------------------------------------------------------------- properties

    /**
     * Every property the deserializer binds by name: the resolved properties, the creator parameters
     * (injection-only ones excluded), and the properties the builder assembled that resolution moved
     * out of the property map into a handler of its own — an unwrapped member, which is described
     * separately, and a member typed through an external type id, which is described here.
     */
    private List<SettableBeanProperty> boundProperties(BeanDeserializerBase bean, BeanDeserializerBuilder builder) {
        List<SettableBeanProperty> all = new ArrayList<>();
        Set<String> names = new HashSet<>();
        bean.properties().forEachRemaining(property -> {
            if (names.add(property.getName())) {
                all.add(property);
            }
        });
        bean.creatorProperties().forEachRemaining(property -> {
            if (property instanceof CreatorProperty creator && creator.isInjectionOnly()) {
                return;
            }
            if (names.add(property.getName())) {
                all.add(property);
            }
        });
        if (builder != null) {
            Iterator<SettableBeanProperty> it = builder.getProperties();
            while (it.hasNext()) {
                SettableBeanProperty property = it.next();
                if (names.contains(property.getName())
                        || property.getMember() == null
                        || introspector().findUnwrappingNameTransformer(property.getMember()) != null) {
                    continue;
                }
                names.add(property.getName());
                all.add(property);
            }
        }
        return all;
    }

    /**
     * The schema of one bound property, or {@code null} when the member is hidden from the document
     * on purpose ({@code @Schema(hidden = true)}).
     */
    private JsonNode propertySchema(
            SettableBeanProperty property,
            Class<?> builtClass,
            ResolvedType resolved,
            SchemaGenerationContext context,
            List<String> required) {
        AnnotatedMember member = property.getMember();
        Member raw = member == null ? null : member.getMember();
        // S-1 (round 5 review finding): unwrap() alone strips only a TypeWrappedDeserializer; under a
        // mapper-wide BeanDeserializerModifier that wraps every bean deserializer in a forwarding
        // DelegatingDeserializer, the member's own contextual deserializer is still that wrapper, so the
        // instanceof BeanDeserializerBase check below missed a case-insensitive member entirely before
        // this fix. unwrapDelegating applies the same W-1 bound here as at the root seam: it unwraps a
        // pure forwarder, and leaves a shape-changing DelegatingDeserializer subclass un-unwrapped (which
        // then simply fails the instanceof check below, exactly as an unrelated non-bean deserializer
        // already does).
        JsonDeserializer<?> valueDeserializer =
                property.hasValueDeserializer() ? unwrapDelegating(unwrap(property.getValueDeserializer())) : null;
        if (valueDeserializer instanceof BeanDeserializerBase nestedBean) {
            // Case-insensitive only through this member's own contextual
            // @JsonFormat(with = ACCEPT_CASE_INSENSITIVE_PROPERTIES) (or a mapper-wide feature reaching
            // it the same way): the type's ordinary, case-sensitive shared definition would misdescribe
            // it here, so it is described inline instead of by reference to that shared definition.
            boolean caseInsensitiveInline = nestedBean.isCaseInsensitive();
            // D005, T005: a member-level @Schema(additionalProperties = FALSE) closes this member's own
            // extras when its resolved value type would describe extras — read from the resolved
            // deserializer's own builder (never live reflection, which would inline a
            // @JsonDeserialize(using=...) type's own internals past the F1 refusal this class relies on
            // elsewhere). A Map-typed member never reaches this branch at all: its own deserializer
            // resolves to a MapDeserializer, not a BeanDeserializerBase, so T003's own Q1 rule (the
            // annotation stays ignored there) is untouched.
            // Security review finding (T005 round 2, MEDIUM): "would describe extras" is not only the
            // value type's own builder any-setter — an any-setter reachable only through the value type's
            // own @JsonUnwrapped child (Y { @JsonUnwrapped Z z }, Z carrying the any-setter) closes the
            // same way. wouldDescribeExtras(BeanDeserializerBuilder) below is the one place this question is
            // answered, shared with populateObjectSchema's own extrasWillBeDescribed so the two triggers
            // never diverge; it stops at one level of unwrapping, same as requireNoNestedUnwrapping does
            // once extras actually need describing.
            // Security review finding (T005 round 2): builderFor(memberType, nestedBean) is a first-touch
            // capturing-mapper probe that can throw, so it must never run for a plain bean-valued member
            // that carries no member-level FALSE at all — memberLevelAdditionalPropertiesFalse(member) is
            // checked first and short-circuits the probe, keeping a plain member byte-identical to before
            // this task (no probe, ordinary $ref).
            JavaType memberType = property.getType();
            boolean extrasSuppressed = memberLevelAdditionalPropertiesFalse(member)
                    && wouldDescribeExtras(builderFor(memberType, nestedBean));
            if (caseInsensitiveInline || extrasSuppressed) {
                // F4 (security review round 1, MEDIUM)/D005 (T005): the shared inline-description helper
                // runs the override-first check, the distinct inlineInProgress recursion bound, the
                // requireNotDelegating and scalar-creator checks, and populateObjectSchema itself — the
                // same machinery T004's own any-setter-conjunction inline path (inlineBeanSchema) reuses,
                // so both the CI-inline and CI-plus-FALSE (or plain-FALSE) branches are bounded the same
                // way. A null return means a (non-extras-suppressed) profile override applies: the caller
                // falls back to the ordinary reference path below, exactly as before this task.
                JsonNode schema =
                        inlineMemberSchema(memberType, nestedBean, property.getName(), extrasSuppressed, context);
                if (schema == null) {
                    // Delegate to the ordinary reference path: it re-enters the full provider chain, where
                    // ProfileOverrideDefinitionProvider — registered ahead of this describer — applies the
                    // override exactly as it would for any other position, instead of this inline path
                    // silently building its own case-insensitive description over it.
                    schema = context.createDefinitionReference(resolve(context, memberType));
                }
                if (member != null) {
                    translateConstraints(
                            member, builtClass, (ObjectNode) schema, property.getName(), property.getType(), required);
                }
                return schema;
            }
        }
        if (valueDeserializer instanceof StdDelegatingDeserializer<?> converting
                && converting.getDelegatee() != null
                && converting.getDelegatee().handledType() != null) {
            // A converter binds the wire value as its delegate's type, not as the member's declared type.
            JsonNode schema = context.createDefinitionReference(
                    context.getTypeContext().resolve(converting.getDelegatee().handledType()));
            if (member != null) {
                translateConstraints(
                        member, builtClass, (ObjectNode) schema, property.getName(), property.getType(), required);
            }
            return schema;
        }
        // rest-023 T003 (D001): a Map-typed member is a distinct position — see mapMemberSchema's own
        // Javadoc for the Q1 WARN and the N1 leakage control. Checked ahead of the Field/Method/Parameter
        // dispatch below so it covers a field-, getter-, setter-, or creator-parameter-backed Map member
        // uniformly, through one path.
        if (property.getType().isMapLikeType()) {
            JsonNode mapSchema = mapMemberSchema(property, member, raw, builtClass, context);
            if (mapSchema != null) {
                return mapSchema;
            }
        }
        if (raw instanceof Field field) {
            return fieldSchema(field, builtClass, resolved, property.getName(), property.getType(), context, required);
        }
        if (raw instanceof Method method) {
            return methodSchema(method, property, builtClass, resolved, context, required);
        }
        if (member instanceof AnnotatedParameter parameter) {
            Field backing = backingField(builtClass, parameter, property.getName());
            if (backing != null) {
                JsonNode schema = fieldSchema(
                        backing, builtClass, resolved, property.getName(), property.getType(), context, required);
                if (schema != null && member != null) {
                    translateConstraints(
                            member, builtClass, (ObjectNode) schema, property.getName(), property.getType(), required);
                }
                return schema;
            }
        }
        // A member the schema library has no scope for: the type from the deserializer, the
        // constraints from Jackson's merged annotation map.
        ObjectNode schema = context.createDefinitionReference(resolve(context, property.getType()));
        if (member != null) {
            translateConstraints(member, builtClass, schema, property.getName(), property.getType(), required);
        }
        boolean objectId = property instanceof com.fasterxml.jackson.databind.deser.impl.ObjectIdValueProperty;
        if (!objectId
                && property.getMetadata() != null
                && Boolean.TRUE.equals(property.getMetadata().getRequired())) {
            required.add(property.getName());
        }
        return schema;
    }

    /**
     * Handles a {@code Map}-typed member as a distinct position (rest-023 T003, {@code D001}): emits the
     * Q1 WARN when the member or its getter carries an {@code @Schema(additionalProperties = ...)}
     * annotation (ignored either way — the map's entries are its own content, so there is nothing
     * "additional" to forbid), and — only when the member's own value position carries a walk-vocabulary
     * type-use overlay anywhere in its own, possibly nested, {@code Map} content (N1, N9) — renders the
     * member's own schema entirely inline, never asking the schema library for a {@code FieldScope}/
     * {@code MethodScope}-based definition at all for this member. This is the N1 leakage control: two
     * members can share the exact same underlying {@code Map<K,V>} type while only one of them carries a
     * type-use overlay on {@code V}, and {@link #provideCustomSchemaDefinition} receives only the
     * resolved type, never the member — so a member-specific overlay can never safely be written into
     * whatever definition the schema library might create or share for that type. Returns {@code null}
     * when the member carries no overlay at all (including when it has no recognizable {@link
     * AnnotatedType} of its own — a raw, non-parameterized {@code Map} declaration, or a member kind this
     * method does not resolve one for), leaving the caller's own pre-existing field/method/parameter
     * dispatch to render it exactly as before this task — now correctly reaching {@link #describeMapLike}
     * for the base rendering, this task's own reachability fix.
     *
     * @param property   the property being described
     * @param member     the property's own Jackson member, possibly {@code null}
     * @param raw        the property's own raw {@link Field} or {@link Method}, possibly {@code null}
     * @param builtClass the type being described, named in the WARN message
     * @param context    the active generation context
     * @return the member's own inline schema, or {@code null} when no overlay applies
     */
    private JsonNode mapMemberSchema(
            SettableBeanProperty property,
            AnnotatedMember member,
            Member raw,
            Class<?> builtClass,
            SchemaGenerationContext context) {
        warnIfAdditionalPropertiesAnnotationIgnored(member, builtClass, property.getName());
        AnnotatedType annotatedType;
        if (member instanceof AnnotatedParameter parameter) {
            Member owner =
                    parameter.getOwner() == null ? null : parameter.getOwner().getMember();
            annotatedType = ValuePositionRenderer.mapValueSlotOfParameter(owner, parameter.getIndex());
        } else if (raw != null) {
            annotatedType = ValuePositionRenderer.mapValueSlotOfMember(raw);
        } else {
            annotatedType = null;
        }
        JavaType content = property.getType().getContentType();
        if (annotatedType == null
                || content == null
                || !ValuePositionRenderer.hasOverlayAnywhere(content, annotatedType)) {
            return null;
        }
        JsonNode valueSchema = valuePositionRenderer.renderValueSchema(
                context, new ValuePositionRenderer.ValuePosition(content, annotatedType, property.getName()));
        ObjectNode schema = context.getGeneratorConfig().createObjectNode();
        schema.put("type", "object");
        if (valueSchema != null) {
            schema.set("additionalProperties", valueSchema);
        }
        return schema;
    }

    /**
     * Emits the Q1 WARN (rest-023 T003, {@code D001}, owner decision Q1) exactly once, naming {@code
     * member}'s own declaring class and name, when {@code member} (or its paired field/getter, through
     * Jackson's own merged annotation resolution — the same {@code member.getAnnotation(Schema.class)}
     * lookup {@link #translateConstraints} already uses) carries an {@code @Schema(additionalProperties =
     * TRUE|FALSE)} declaration. The annotation has no effect on the generated input schema for a
     * {@code Map}-typed member either way; this WARN is the only new behavior this rule adds. Never fails
     * generation.
     *
     * @param member     the property's own Jackson member, possibly {@code null}
     * @param builtClass the type being described, named in the message
     * @param wireName   the property's own wire name, named in the message
     */
    private static void warnIfAdditionalPropertiesAnnotationIgnored(
            AnnotatedMember member, Class<?> builtClass, String wireName) {
        if (member == null) {
            return;
        }
        Schema swagger = member.getAnnotation(Schema.class);
        if (swagger == null
                || swagger.additionalProperties()
                        == Schema.AdditionalPropertiesValue.USE_ADDITIONAL_PROPERTIES_ANNOTATION) {
            return;
        }
        LOG.log(
                Level.WARNING,
                builtClass.getName() + "#" + wireName
                        + ": @Schema(additionalProperties) on a Map-typed member has no effect on the generated"
                        + " input schema");
    }

    /**
     * Whether {@code member} itself carries {@code @Schema(additionalProperties = FALSE)} (D005, T005) —
     * read through the same {@code member.getAnnotation(Schema.class)} lookup {@link
     * #translateConstraints} and {@link #warnIfAdditionalPropertiesAnnotationIgnored} already use, never
     * live reflection on the member's own value type.
     *
     * @param member the property's own Jackson member, possibly {@code null}
     * @return {@code true} when the member declares {@code additionalProperties = FALSE}
     */
    private static boolean memberLevelAdditionalPropertiesFalse(AnnotatedMember member) {
        if (member == null) {
            return false;
        }
        Schema schema = member.getAnnotation(Schema.class);
        return schema != null && schema.additionalProperties() == Schema.AdditionalPropertiesValue.FALSE;
    }

    private JsonNode fieldSchema(
            Field field,
            Class<?> builtClass,
            ResolvedType resolved,
            String name,
            JavaType propertyType,
            SchemaGenerationContext context,
            List<String> required) {
        TypeContext typeContext = context.getTypeContext();
        SchemaGeneratorConfig config = context.getGeneratorConfig();
        ResolvedTypeWithMembers members = membersOf(typeContext, resolved, field.getDeclaringClass());
        for (ResolvedField candidate : members.getMemberFields()) {
            if (!candidate.getRawMember().equals(field)) {
                continue;
            }
            FieldScope scope = typeContext.createFieldScope(
                    candidate, new MemberScope.DeclarationDetails(candidate.getDeclaringType(), members));
            if (isHidden(scope)) {
                return null;
            }
            boolean nullable = config.isNullable(scope);
            if (config.isRequired(scope)) {
                required.add(name);
            }
            List<ResolvedType> overrides = config.resolveTargetTypeOverrides(scope);
            if (overrides != null && overrides.size() == 1) {
                scope = scope.withOverriddenType(overrides.get(0));
            }
            ObjectNode schema = context.createStandardDefinitionReference(scope, null);
            if (nullable) {
                markNullable(schema);
            }
            applyScopedConstraints(schema, builtClass, field.getName(), propertyType.getRawClass(), name, required);
            return schema;
        }
        // A field the library's member resolution does not list (a static or synthetic one): by type.
        return context.createDefinitionReference(typeContext.resolve(field.getGenericType()));
    }

    /**
     * When a Bean Validation {@link #supplement} is active, merges its constraints and required-ness
     * onto a field or getter that a schema-library member scope — and therefore the always-active
     * Jakarta Validation module — already described (C2, design correction): an addition only fills a
     * keyword the module left unset, and a correction (#606: {@code @Range}, {@code @Length}, {@code
     * @URL}, a {@code @Pattern} flag) replaces the module's own rendering unconditionally. A no-op when
     * no supplement is active, leaving the module's own rendering exactly as it stood before this
     * class existed.
     *
     * @param schema     the schema the library produced for the member, already carrying whatever the
     *                   always-active Jakarta Validation module rendered
     * @param builtClass the type the property is described on
     * @param javaName   the field's or getter's Java bean name
     * @param javaType   the member's Java type, which selects the size/range keyword family (C2: derived
     *                   from the Java type, never from the schema's own {@code type}, which is absent
     *                   for a map, a bean, or an {@code Optional} at this point). Resolved as Jackson
     *                   sees it for the built type's own parameterization — the raw {@link Field} or
     *                   {@link java.lang.reflect.Method}'s reflected type erases a type variable to its
     *                   bound (e.g. {@code Object} for an unbounded {@code T}) regardless of what the
     *                   holder actually binds it to; {@code SettableBeanProperty#getType()}'s {@link
     *                   JavaType} does not (D4).
     * @param wireName   the published property name, added to {@code required} when applicable
     * @param required   the object schema's required-property list
     */
    private void applyScopedConstraints(
            ObjectNode schema,
            Class<?> builtClass,
            String javaName,
            Class<?> javaType,
            String wireName,
            List<String> required) {
        if (supplement == null) {
            return;
        }
        ConstraintValueKind kind = ConstraintValueKind.fromJavaType(javaType);
        ResolvedConstraints resolved = supplement.forScopedMember(builtClass, javaName, kind);
        if (resolved.required() && !required.contains(wireName)) {
            required.add(wireName);
        }
        if (resolved.additions().isEmpty() && resolved.corrections().isEmpty()) {
            return;
        }
        // Deferred, not applied here and now (unlike the unscoped path): the schema library's own
        // Jakarta Validation module does not finish writing every attribute — "pattern" under
        // INCLUDE_PATTERN_EXPRESSIONS in particular — by the moment createStandardDefinitionReference
        // returns this node. Measured: writing a "pattern" correction synchronously at this point made
        // the module's own later write see a pre-existing, differing value and defensively wrap both
        // into allOf instead of the one corrected value ever winning — a double-pattern, over-constrained
        // document. The marker technique already proven for nullability and alias expansion in this
        // file solves the same "must wait for the finished document" problem, so scoped corrections use
        // it too: {@link AnnotationJsonSchemaGenerator#generateCanonical} applies and strips this marker
        // right after the underlying generator call returns, before nullability and alias expansion run
        // (so an alias copy, and a nullable wrap, both see the corrected value, never the module's own).
        ObjectNode marker = schema.putObject(SCOPED_CONSTRAINTS_MARKER);
        ObjectNode additionsNode = marker.putObject("additions");
        if (resolved.additions().containsKey("items")) {
            additionsNode.putObject("items");
        }
        resolved.additions().forEach((key, value) -> putKeyword(additionsNode, key, value));
        ObjectNode correctionsNode = marker.putObject("corrections");
        if (resolved.corrections().containsKey("items")) {
            correctionsNode.putObject("items");
        }
        resolved.corrections().forEach((key, value) -> putKeyword(correctionsNode, key, value));
    }

    /**
     * The generator-private keyword carrying a scoped member's deferred addition/correction plan — see
     * {@link #applyScopedConstraints}. Applied and stripped by {@link
     * #applyDeferredScopedConstraints(JsonNode)}, called once the underlying schema library has fully
     * finished generating the document.
     */
    static final String SCOPED_CONSTRAINTS_MARKER = "x-vertique-scoped-constraints";

    /**
     * Applies every deferred scoped-member addition/correction plan the document carries, and strips
     * the marker, once the underlying schema library has fully finished writing to every node —
     * including whatever it defers past the point {@link #applyScopedConstraints} ran at.
     *
     * <p>An addition is applied only where the target schema does not already carry that keyword; a
     * correction unconditionally, replacing whatever the schema library's own module wrote for it. The
     * one keyword needing special handling is {@code "items"}: its value is itself a nested keyword
     * object for a container-element position, merged into the schema's own {@code items} subschema —
     * only when that subschema already exists as an inline object — rather than replacing it whole.
     *
     * @param document the generated document, before nullability and alias expansion — both of which
     *                 must see the corrected values, not the schema library's own pre-correction ones
     */
    static void applyDeferredScopedConstraints(JsonNode document) {
        AnnotationJsonSchemaGenerator.AliasExpansion.walkSchemaPositions(document, schema -> {
            JsonNode marker = schema.remove(SCOPED_CONSTRAINTS_MARKER);
            if (marker == null) {
                return;
            }
            applyEncodedKeywords(schema, (ObjectNode) marker.get("additions"), false);
            applyEncodedKeywords(schema, (ObjectNode) marker.get("corrections"), true);
        });
    }

    /**
     * Applies one encoded keyword set onto a finished schema node; see
     * {@link #applyDeferredScopedConstraints}.
     *
     * <p>F7 (security review round 1, LOW): for a correction ({@code overwrite == true}), a bound
     * keyword ({@link #MIN_BOUND_KEYWORDS}/{@link #MAX_BOUND_KEYWORDS}) the schema already carries a
     * numeric value for is only overwritten by a value at least as strict as the existing one — see
     * {@link #applyCorrection}, the unscoped-member counterpart this mirrors — and a differing {@code
     * pattern} is combined with the existing one as an {@code allOf} instead of replacing it. An
     * addition ({@code overwrite == false}) is unaffected: it is still applied only where the schema
     * does not already carry that keyword.
     *
     * <p>S2 (CO-001, the rest-021 package's carried obligation): an {@code "allOf"} correction — the
     * encoded form of a scoped member's own two-{@code @Pattern} rendering, see {@link
     * MetadataConstraintSource}'s {@code render} — is appended onto the schema's existing {@code allOf}
     * array through {@link #appendAllOfPatterns}, the same helper {@link #putKeyword}'s own {@code
     * "allOf"} branch calls, rather than falling through to the unconditional {@code schema.set(key,
     * value)} below and replacing whatever {@code allOf} the schema already carried. Mirrors {@link
     * #putKeyword} rather than being covered by its own direct test: this method is {@code private}, and
     * the schema-generation walk never itself produces a scoped member whose encoded corrections already
     * carry a two-pattern {@code allOf} alongside a pre-existing {@code allOf} on the target schema, so
     * there is no reachable path a test could drive through the public generator to exercise this branch;
     * the fix is proven correct by construction, identical to the {@link #putKeyword} composition {@link
     * KeywordCompositionTest} proves directly.
     */
    private static void applyEncodedKeywords(ObjectNode schema, ObjectNode encoded, boolean overwrite) {
        encoded.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if ("items".equals(key) && value.isObject() && schema.get("items") instanceof ObjectNode itemsObject) {
                value.properties().forEach(inner -> {
                    if (overwrite || !itemsObject.has(inner.getKey())) {
                        itemsObject.set(inner.getKey(), inner.getValue());
                    }
                });
                return;
            }
            if (!overwrite) {
                if (!schema.has(key)) {
                    schema.set(key, value);
                }
                return;
            }
            if ("pattern".equals(key) && value.isTextual() && schema.get("pattern") instanceof TextNode existing) {
                mergePatternAsAllOf(schema, existing.asText(), value.asText());
                return;
            }
            if ("allOf".equals(key) && value instanceof ArrayNode incoming) {
                appendAllOfPatterns(schema, List.copyOf(allOfPatterns(incoming)));
                return;
            }
            if ((MIN_BOUND_KEYWORDS.contains(key) || MAX_BOUND_KEYWORDS.contains(key))
                    && schema.get(key) instanceof JsonNode existing
                    && existing.isNumber()
                    && value.isNumber()
                    && !isStricterOrEqual(key, value.decimalValue(), existing.decimalValue())) {
                return; // the floor's existing bound is already at least as strict; keep it
            }
            schema.set(key, value);
        });
    }

    private JsonNode methodSchema(
            Method method,
            SettableBeanProperty property,
            Class<?> builtClass,
            ResolvedType resolved,
            SchemaGenerationContext context,
            List<String> required) {
        TypeContext typeContext = context.getTypeContext();
        SchemaGeneratorConfig config = context.getGeneratorConfig();
        AnnotatedMember member = property.getMember();
        boolean setter = method.getReturnType() == void.class || method.getParameterCount() > 0;
        if (setter) {
            // The library's method scope models a return type; a setter has none, and a builder method
            // returns the builder. The value type comes from the deserializer, the constraints from
            // Jackson's merged annotation map, and a builder method borrows the built type's field.
            ObjectNode schema = context.createDefinitionReference(resolve(context, property.getType()));
            BeanPropertyDefinition builtProperty =
                    translateConstraints(member, builtClass, schema, property.getName(), property.getType(), required);
            if (method.getDeclaringClass() != builtClass
                    && !method.getDeclaringClass().isAssignableFrom(builtClass)) {
                // a builder method: the constraints are borrowed from the built type's own Jackson
                // property of the same wire name — the same BeanPropertyDefinition translateConstraints
                // just resolved above, never a second, independent lookup — since Jackson's own
                // introspection is the only guarantee available here: it says which field a property of
                // that wire name means, never what the builder method's own body does with the value
                // before storing it. When that property has a getter the borrow is published
                // unconditionally, for any builder; when it has no getter, the borrow is published only
                // when BuilderBorrowDetector judges it sound (a Lombok builder, or the exact Lombok
                // builder shape) — see module.md's "Builder borrow assumption" for the ruling and the
                // documented consequence for a getter-less, hand-written builder that goes unresolved.
                borrowBuilderFieldAttributes(
                        method, builtClass, property.getName(), builtProperty, schema, context, required);
            } else {
                // a setter: the field Jackson's own introspection merges into the same wire-named
                // property — transient, private, or renamed on the wire — still carries the
                // constraints the developer wrote for the value.
                borrowFieldAttributes(builtClass, property.getName(), schema, context, required);
            }
            return schema;
        }
        ResolvedTypeWithMembers members = membersOf(typeContext, resolved, method.getDeclaringClass());
        for (ResolvedMethod candidate : members.getMemberMethods()) {
            if (!candidate.getRawMember().equals(method)) {
                continue;
            }
            MethodScope scope = typeContext.createMethodScope(
                    candidate, new MemberScope.DeclarationDetails(candidate.getDeclaringType(), members));
            if (isHidden(scope)) {
                return null;
            }
            boolean nullable = config.isNullable(scope);
            if (config.isRequired(scope)) {
                required.add(property.getName());
            }
            List<ResolvedType> overrides = config.resolveTargetTypeOverrides(scope);
            if (overrides != null && overrides.size() == 1) {
                scope = scope.withOverriddenType(overrides.get(0));
            }
            JsonNode schema = context.createStandardDefinitionReference(scope, null);
            if (schema instanceof ObjectNode object) {
                if (nullable) {
                    markNullable(object);
                }
                applyScopedConstraints(
                        object,
                        builtClass,
                        getterBeanName(method),
                        property.getType().getRawClass(),
                        property.getName(),
                        required);
            }
            return schema;
        }
        ObjectNode schema = context.createDefinitionReference(resolve(context, property.getType()));
        translateConstraints(member, builtClass, schema, property.getName(), property.getType(), required);
        return schema;
    }

    /**
     * The built type's <em>Jackson-introspected</em> field for the given wire name, whose attributes a
     * setter borrows — a raw field-name scan by declaration order, over the setter's own implied Java
     * name or the wire name, joined the shape a coincidental match could misattribute a constraint from
     * an unrelated field of the same name (the exact join class D002/D003 rejected as unsound for a
     * creator parameter's own field join, review INFO item). Joining through {@code
     * BeanPropertyDefinition#getField()} instead uses Jackson's own merge: the field it associates with
     * the same wire-named property is guaranteed to be the one this setter's own value corresponds to,
     * mirroring {@link #borrowBuilderFieldAttributes}'s own join exactly. Transient, private, or renamed
     * on the wire, the field's constraints still carry the constraints the developer wrote for the
     * value.
     *
     * <p>W1 (spike/deserializer-driven-schema round 4 ruling): {@code getField()} is {@code null} when
     * the only accessor Jackson associates with the property is the setter itself — a private field with
     * a setter and no getter, no public field either — so there is nothing to join through Jackson's own
     * merge. Bounded to exactly that shape (no getter either — never overriding Jackson's own {@code
     * getField()} join when one resolves), this falls back to the field whose Java name equals the
     * setter's own implied name (the JavaBean convention the method name itself implies, e.g. {@code
     * setLevel} implies {@code level}), the same implied-name convention {@link #impliedFieldName}
     * already names for this purpose.
     *
     * <p>Reopened (round 6 finding): {@code getField()} is {@code null} for exactly the same reason for a
     * {@code transient} field — Jackson's property definition carries no field member for it either, not
     * only when the field is otherwise inaccessible — regardless of whether a getter is also present. The
     * implied-name fallback above was wrongly bounded to "no field member <em>and</em> no getter"; it now
     * fires whenever there is no field member, whether or not a getter exists, since the absence of a
     * field member — not the presence or absence of a getter — is what leaves Jackson's own merge with
     * nothing to join through. The getter-present-and-field-present path above is unchanged.
     */
    private void borrowFieldAttributes(
            Class<?> builtClass,
            String wireName,
            ObjectNode schema,
            SchemaGenerationContext context,
            List<String> required) {
        BeanDescription description = introspection(mapper.getTypeFactory().constructType(builtClass));
        for (BeanPropertyDefinition candidate : description.findProperties()) {
            if (!candidate.getName().equals(wireName)) {
                continue;
            }
            AnnotatedField field = candidate.getField();
            if (field != null) {
                applyFieldScopeAttributes(
                        field.getAnnotated(), field.getDeclaringClass(), wireName, schema, context, required);
            } else {
                AnnotatedMethod setter = candidate.getSetter();
                Field implied = setter == null ? null : fieldNamed(builtClass, impliedFieldName(setter.getAnnotated()));
                if (implied != null) {
                    applyFieldScopeAttributes(implied, builtClass, wireName, schema, context, required);
                }
            }
            return;
        }
    }

    /**
     * The built type's <em>Jackson-introspected</em> property of the given wire name, whose attributes
     * a builder method borrows — never a raw field-name scan, unlike {@link #borrowFieldAttributes}.
     *
     * <p>Round 2 (this task's owner ruling): when the property has a getter and a backing field, the
     * borrow is published unconditionally, for any builder — exactly what {@code main}'s field walk did, since a
     * getter-backed field's constraints were always published there regardless of what any builder
     * method's body did with the value before assigning it. Only when the property has no getter (the
     * private, getter-less case {@code main}'s field walk never published either) does the borrow stay
     * bounded to the shapes {@link BuilderBorrowDetector} can vouch for: a Lombok {@code @Builder}
     * setter is guaranteed by construction to set the built field of that same property, so borrowing
     * through Jackson's own introspected wire name renders identically to the raw scan this replaces
     * for that guaranteed shape, while a hand-written builder that transforms the value before
     * assigning it carries no such guarantee and, absent a getter, is published by type only — see
     * {@code module.md}'s "Builder borrow assumption".
     *
     * @param builtProperty the built type's Jackson-introspected property for {@code wireName}, already
     *                      resolved once by {@link #translateConstraints} — never looked up again here
     */
    private void borrowBuilderFieldAttributes(
            Method builderMethod,
            Class<?> builtClass,
            String wireName,
            BeanPropertyDefinition builtProperty,
            ObjectNode schema,
            SchemaGenerationContext context,
            List<String> required) {
        if (builtProperty == null) {
            return;
        }
        AnnotatedField field = builtProperty.getField();
        if (field != null
                && (BuilderBorrowDetector.isGetterBacked(builtProperty)
                        || BuilderBorrowDetector.isSoundBorrow(builderMethod, builtClass, field.getAnnotated()))) {
            applyFieldScopeAttributes(
                    field.getAnnotated(), field.getDeclaringClass(), wireName, schema, context, required);
        }
    }

    /** Merges one field's schema-library attributes onto {@code schema}, shared by both borrow paths above. */
    private static void applyFieldScopeAttributes(
            Field field,
            Class<?> declaringClass,
            String wireName,
            ObjectNode schema,
            SchemaGenerationContext context,
            List<String> required) {
        TypeContext typeContext = context.getTypeContext();
        ResolvedTypeWithMembers members = typeContext.resolveWithMembers(typeContext.resolve(declaringClass));
        for (ResolvedField candidate : members.getMemberFields()) {
            if (candidate.getRawMember().equals(field)) {
                FieldScope scope = typeContext.createFieldScope(
                        candidate, new MemberScope.DeclarationDetails(candidate.getDeclaringType(), members));
                AttributeCollector.mergeMissingAttributes(
                        schema, AttributeCollector.collectFieldAttributes(scope, context));
                if (context.getGeneratorConfig().isRequired(scope)) {
                    required.add(wireName);
                }
                return;
            }
        }
    }

    /**
     * Whether the member is hidden from the document on purpose. Only Swagger's {@code hidden} counts:
     * the schema library's own ignore checks encode its walk policy — which methods it walks, which
     * fields it skips — and a member the deserializer binds is described whatever that policy says.
     */
    private static boolean isHidden(MemberScope<?, ?> scope) {
        Schema schema = scope.getAnnotationConsideringFieldAndGetter(Schema.class);
        return schema != null && schema.hidden();
    }

    /** The field a setter implies by its name: {@code setLevel} implies {@code level}. */
    private static String impliedFieldName(Method method) {
        return stripAccessorPrefix(method.getName(), SETTER_PREFIXES);
    }

    /**
     * The field behind a creator parameter: the record component's field — exact, because component
     * {@code i} is parameter {@code i} by language definition only for the record's <em>canonical</em>
     * constructor ({@link #isCanonicalRecordConstructor}) — or, for every other creator (including a
     * non-canonical {@code @JsonCreator} constructor whose parameter order differs from the component
     * order, and a record's {@code @JsonCreator} static factory, which is an {@code AnnotatedMethod} and
     * so never reaches the by-index branch at all), the field of the same wire name, which is Jackson's
     * own statement that the two are one logical property (Jackson merges a field and a creator
     * parameter into one {@code BeanPropertyDefinition} only when they share a name). A field whose own
     * Java name merely coincides with the parameter's compiled name is never a candidate: a compiled
     * parameter name says nothing about which field, if any, a constructor assigns it to, and joining on
     * it borrowed an unrelated field's constraint onto a transforming constructor's parameter (a
     * value-changing assignment, or a coincidental field-name match), rejecting or accepting traffic the
     * binder itself would not.
     */
    private static Field backingField(Class<?> builtClass, AnnotatedParameter parameter, String wireName) {
        if (builtClass.isRecord() && isCanonicalRecordConstructor(builtClass, parameter.getOwner())) {
            RecordComponent[] components = builtClass.getRecordComponents();
            int index = parameter.getIndex();
            if (index >= 0 && index < components.length) {
                try {
                    return builtClass.getDeclaredField(components[index].getName());
                } catch (NoSuchFieldException absent) {
                    return null;
                }
            }
        }
        return fieldNamed(builtClass, wireName);
    }

    /**
     * Whether {@code owner} is {@code builtClass}'s canonical record constructor: an {@link
     * AnnotatedConstructor} whose parameter types equal, in order, the record components' types
     * ({@link RecordComponent#getType()}) and count. Only the canonical constructor guarantees that
     * component {@code i} is parameter {@code i}; any other constructor — reachable through an explicit
     * {@code @JsonCreator} whose parameter order differs from the component order — must instead be
     * joined by wire name ({@link #fieldNamed}), or it borrows an unrelated component's type and
     * constraints onto the wrong property.
     */
    private static boolean isCanonicalRecordConstructor(Class<?> builtClass, AnnotatedWithParams owner) {
        if (!(owner instanceof AnnotatedConstructor)) {
            return false;
        }
        RecordComponent[] components = builtClass.getRecordComponents();
        if (owner.getParameterCount() != components.length) {
            return false;
        }
        for (int i = 0; i < components.length; i++) {
            if (!owner.getRawParameterType(i).equals(components[i].getType())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The declared, non-static, non-synthetic field of the given Java name, found by walking
     * {@code builtClass} and its superclasses in declaration order — shared by {@link #backingField}'s
     * own non-record lookup and, for W1 (spike/deserializer-driven-schema round 4 ruling), {@link
     * #borrowFieldAttributes}'s fallback join when Jackson's own {@code BeanPropertyDefinition#getField()}
     * has nothing to join through.
     */
    private static Field fieldNamed(Class<?> builtClass, String javaName) {
        for (Class<?> current = builtClass;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && !field.isSynthetic()
                        && field.getName().equals(javaName)) {
                    return field;
                }
            }
        }
        return null;
    }

    /**
     * The constraints a member without a library scope needs — a creator parameter, a setter, or a
     * builder method. {@link WalkConstraintSource} — the floor, reading Jackson's merged annotation
     * map directly — always runs first, whether or not a Bean Validation {@link #supplement} is also
     * active: this is what keeps a static-factory creator parameter's own constraint from being
     * silently dropped when a validator is supplied (C3), since Bean Validation itself can join a
     * creator parameter only through a constructor. The supplement, when active, is merged on top —
     * see {@link #mergeConstraints}. The Swagger translation is source-independent and always applied.
     *
     * @param propertyType the property's own Jackson-resolved value type (D4, unscoped-member
     *                      counterpart): a builder or setter {@code member}'s {@link
     *                      AnnotatedMember#getRawType()} is that <em>method's</em> raw type, which for
     *                      Jackson's own {@link com.fasterxml.jackson.databind.introspect.AnnotatedMethod}
     *                      is its return type — the builder's own type for a builder method, {@code
     *                      void} for an ordinary setter — never the parameter the value actually binds
     *                      as; only {@code property}'s own type says that
     * @return the built type's Jackson-introspected property for {@code name} (the wire name), resolved
     *         once here and shared with {@link ConstraintSource#forUnscopedMember}'s callers below and
     *         with the builder-method borrow ({@link #borrowBuilderFieldAttributes}) a caller may run
     *         next for the same member; {@code null} when Jackson reports no property of that wire name
     *         for the built type at all
     */
    private BeanPropertyDefinition translateConstraints(
            AnnotatedMember member,
            Class<?> builtClass,
            ObjectNode schema,
            String name,
            JavaType propertyType,
            List<String> required) {
        if (member == null) {
            return null;
        }
        ConstraintValueKind kind = ConstraintValueKind.fromJavaType(propertyType.getRawClass());
        String javaName = javaBeanName(member);
        // Resolved once, by wire name, from the cached introspection — the single choke point every
        // unscoped-member call site goes through — so the floor and the supplement below can never
        // select a different built property for the same member (see ConstraintSource's own
        // "Resolved-definition contract").
        BeanPropertyDefinition builtProperty = builtPropertyByWireName(builtClass, name);
        ResolvedConstraints floor =
                WalkConstraintSource.INSTANCE.forUnscopedMember(builtClass, javaName, kind, member, builtProperty);
        mergeConstraints(schema, floor, name, required);
        if (supplement != null) {
            ResolvedConstraints resolved =
                    supplement.forUnscopedMember(builtClass, javaName, kind, member, builtProperty);
            mergeConstraints(schema, resolved, name, required);
        }
        Schema swagger = member.getAnnotation(Schema.class);
        if (swagger != null) {
            translateSwagger(swagger, schema);
        }
        return builtProperty;
    }

    /**
     * The built type's Jackson-introspected property for the given wire name, from the cached {@link
     * #introspection} — the same lookup {@link #borrowBuilderFieldAttributes} ran independently before
     * this method existed — or {@code null} when Jackson's own introspection reports no property of
     * that wire name for the built type at all.
     */
    private BeanPropertyDefinition builtPropertyByWireName(Class<?> builtClass, String wireName) {
        BeanDescription description = introspection(mapper.getTypeFactory().constructType(builtClass));
        for (BeanPropertyDefinition candidate : description.findProperties()) {
            if (candidate.getName().equals(wireName)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Merges a resolved constraint set onto a schema and its required-list entry: an addition is
     * applied only where the schema does not already carry that keyword, a correction through {@link
     * #applyCorrection} — see {@link ResolvedConstraints}. {@code "items"} is always applied regardless
     * of whether the schema already carries the keyword: it names a nested keyword map for a
     * container-element position, and {@link #putKeyword} itself merges only the individual nested keys
     * that are unset, never overwriting the {@code items} subschema's own structural keywords ({@code
     * type}, ...).
     */
    private static void mergeConstraints(
            ObjectNode schema, ResolvedConstraints resolved, String wireName, List<String> required) {
        for (Map.Entry<String, Object> entry : resolved.additions().entrySet()) {
            if ("items".equals(entry.getKey()) || !schema.has(entry.getKey())) {
                putKeyword(schema, entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, Object> entry : resolved.corrections().entrySet()) {
            applyCorrection(schema, entry.getKey(), entry.getValue());
        }
        if (resolved.required() && !required.contains(wireName)) {
            required.add(wireName);
        }
    }

    /**
     * The bound keywords whose "min" half a stricter (larger) value replaces (F7, security review
     * round 1, LOW).
     */
    private static final Set<String> MIN_BOUND_KEYWORDS =
            Set.of("minimum", "exclusiveMinimum", "minLength", "minItems", "minProperties");

    /**
     * The bound keywords whose "max" half a stricter (smaller) value replaces (F7, security review
     * round 1, LOW).
     */
    private static final Set<String> MAX_BOUND_KEYWORDS =
            Set.of("maximum", "exclusiveMaximum", "maxLength", "maxItems", "maxProperties");

    /**
     * Applies one #606 correction keyword onto a schema the floor already wrote to.
     *
     * <p>F7 (security review round 1, LOW): a correction is keyed by annotation type but applied by
     * keyword, unconditionally — before this method existed, a correction from a different annotation
     * than the one the floor rendered from (e.g. {@code @Range(min = 10, max = 20)} correcting over a
     * floor {@code minimum: 15} rendered from a separate {@code @Min(15)}) silently overwrote a
     * <em>stricter</em> value the floor already had right, loosening the gate below both the floor and
     * the binder — Bean Validation enforces the conjunction of every constraint on a member, not only
     * the last one rendered. When both the floor and this correction set the same bound keyword, the
     * stricter of the two now wins: for a "min" keyword the larger value, for a "max" keyword the
     * smaller one. For {@code pattern}, where "stricter" has no total order, both patterns are kept, as
     * an {@code allOf} of two single-{@code pattern} subschemas — the same shape {@link #putKeyword}
     * already renders for two {@code @Pattern} constraints from the <em>same</em> source (S5). Every
     * other keyword keeps the pre-existing unconditional-overwrite behavior.
     *
     * @param schema the schema the floor already wrote to
     * @param key    the correction's keyword
     * @param value  the correction's value, in the same representation {@link #putKeyword} accepts
     */
    static void applyCorrection(ObjectNode schema, String key, Object value) {
        if ("pattern".equals(key)
                && value instanceof String candidate
                && schema.get("pattern") instanceof TextNode existing) {
            mergePatternAsAllOf(schema, existing.asText(), candidate);
            return;
        }
        if ((MIN_BOUND_KEYWORDS.contains(key) || MAX_BOUND_KEYWORDS.contains(key))
                && schema.get(key) instanceof JsonNode existing
                && existing.isNumber()) {
            BigDecimal candidateValue = numericValue(value);
            if (candidateValue != null && !isStricterOrEqual(key, candidateValue, existing.decimalValue())) {
                return; // the floor's existing bound is already at least as strict; keep it
            }
        }
        putKeyword(schema, key, value);
    }

    /**
     * Whether {@code candidate} is at least as strict as {@code existing} for the given bound keyword:
     * greater-or-equal for a "min" keyword, less-or-equal for a "max" one.
     */
    static boolean isStricterOrEqual(String key, BigDecimal candidate, BigDecimal existing) {
        int comparison = candidate.compareTo(existing);
        return MIN_BOUND_KEYWORDS.contains(key) ? comparison >= 0 : comparison <= 0;
    }

    /** The numeric value a correction keyword's raw value carries, or {@code null} for a non-numeric one. */
    private static BigDecimal numericValue(Object value) {
        if (value instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (value instanceof Integer i) {
            return BigDecimal.valueOf(i);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return null;
    }

    /**
     * Combines two differing {@code pattern} values into an {@code allOf} of one single-{@code pattern}
     * subschema each, removing the plain {@code pattern} keyword — the shape a client must satisfy both
     * regular expressions to pass, matching Bean Validation's own conjunction of the two constraints
     * that rendered them. A no-op when the two patterns are textually identical.
     *
     * <p>Excepted: when {@code candidatePattern} is exactly {@code existingPattern} with an inline Java
     * regex modifier group embedded around it — {@link MetadataConstraintSource#renderPattern}'s own
     * shape for a single {@code @Pattern}'s flags (#606) — the two values are not two different
     * annotations in conflict, only the same one rendered twice at different fidelity: the floor (the
     * schema library's own Jakarta module, or {@link WalkConstraintSource} for an unscoped member)
     * embeds no flags, and this correction is the flag-aware rendering of that identical regexp. The
     * more complete rendering replaces the floor's own outright, exactly as it did before F7 (proven by
     * {@code MetadataConstraintSourceCoverageTest#sharp606Shapes}), rather than being combined with it
     * into a redundant {@code allOf}.
     *
     * <p>Appends to an existing {@code allOf} array rather than replacing it, so a genuine two-source
     * conflict composes with S5's own same-source multi-pattern rendering instead of discarding it.
     */
    static void mergePatternAsAllOf(ObjectNode schema, String existingPattern, String candidatePattern) {
        if (existingPattern.equals(candidatePattern)) {
            return;
        }
        if (embedsFlaggedRegexp(candidatePattern, existingPattern)) {
            schema.put("pattern", candidatePattern);
            return;
        }
        ArrayNode allOf =
                schema.get("allOf") instanceof ArrayNode existingAllOf ? existingAllOf : schema.putArray("allOf");
        allOf.addObject().put("pattern", existingPattern);
        allOf.addObject().put("pattern", candidatePattern);
        schema.remove("pattern");
    }

    /**
     * The {@code pattern} text every existing single-{@code pattern} branch of an {@code allOf} array
     * carries, in encounter order — the set a new pattern is checked against so a composition never
     * appends a duplicate. A branch with no textual {@code pattern} keyword (not this method's own
     * shape) contributes nothing.
     */
    private static Set<String> allOfPatterns(ArrayNode allOf) {
        Set<String> patterns = new LinkedHashSet<>();
        for (JsonNode branch : allOf) {
            if (branch.get("pattern") instanceof TextNode pattern) {
                patterns.add(pattern.asText());
            }
        }
        return patterns;
    }

    /**
     * Appends every pattern in {@code patterns} as a new single-{@code pattern} {@code allOf} branch,
     * skipping one whose text a branch already present in the schema's own {@code allOf} array carries
     * (FR-009: a constraint keyword is never removed, and a composition never duplicates a branch) — the
     * shared merge both {@link #putKeyword}'s {@code "allOf"} branch and {@link #applyEncodedKeywords}'s
     * scoped-member {@code "allOf"} correction call, so an {@code allOf} the schema already carries is
     * always appended to, never replaced whole.
     */
    private static void appendAllOfPatterns(ObjectNode schema, List<String> patterns) {
        ArrayNode allOf =
                schema.get("allOf") instanceof ArrayNode existingAllOf ? existingAllOf : schema.putArray("allOf");
        Set<String> seenPatterns = allOfPatterns(allOf);
        for (String patternText : patterns) {
            if (seenPatterns.add(patternText)) {
                allOf.addObject().put("pattern", patternText);
            }
        }
    }

    /**
     * Whether {@code flagged} is exactly {@code plainRegexp} wrapped in an inline Java regex modifier
     * group — {@code "(?" + modifiers + ":" + plainRegexp + ")"}, {@link
     * MetadataConstraintSource#renderPattern}'s own shape for a single {@code @Pattern}'s embedded
     * flags — meaning both values render the very same {@code @Pattern} annotation, not two different
     * ones in conflict.
     */
    private static boolean embedsFlaggedRegexp(String flagged, String plainRegexp) {
        String suffix = ":" + plainRegexp + ")";
        return flagged.startsWith("(?") && flagged.length() > suffix.length() && flagged.endsWith(suffix);
    }

    /**
     * Applies one constraint-source keyword to a schema, preserving the exact value type the
     * pre-existing hand translation used ({@link Long}/{@link Integer} for an integral bound,
     * {@link BigDecimal} for a decimal bound, {@link String} for a pattern) so a document's numeric
     * formatting is unaffected by which source produced it.
     *
     * <p>{@code "items"} is special: its value is itself a keyword map for a {@code List}/array value
     * position's container-element constraints, merged onto the schema's own {@code items} subschema
     * when that subschema is an inline object — a {@code $ref}'d items subschema is left unchanged,
     * the same gap the generator already accepts for a {@code Map} value position.
     *
     * <p>{@code "allOf"} is also special (S5): its value is a {@link List} of pattern strings, rendered
     * as {@code allOf} branches of single-keyword {@code {"pattern": ...}} objects — the shape two
     * {@code @Pattern} constraints in the default group on one member need, since the schema's
     * {@code pattern} keyword itself can only ever hold one regular expression. This follows the same
     * rule the supplement's corrections follow (FR-009 of the rest-021 package: a constraint keyword is
     * never removed): an existing {@code allOf} array is appended to, never replaced, and a pattern
     * already carried by one of its branches is skipped rather than duplicated — the same composition
     * {@link #mergePatternAsAllOf} already performs for a {@code "pattern"} correction.
     */
    @SuppressWarnings("unchecked")
    private static void putKeyword(ObjectNode schema, String key, Object value) {
        if ("items".equals(key) && value instanceof Map<?, ?> itemKeywords) {
            if (schema.get("items") instanceof ObjectNode itemsObject) {
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) itemKeywords).entrySet()) {
                    putKeyword(itemsObject, entry.getKey(), entry.getValue());
                }
            }
            return;
        }
        if ("allOf".equals(key) && value instanceof List<?> patterns) {
            appendAllOfPatterns(
                    schema, patterns.stream().map(pattern -> (String) pattern).toList());
            return;
        }
        if (value instanceof Long l) {
            schema.put(key, (long) l);
        } else if (value instanceof Integer i) {
            schema.put(key, (int) i);
        } else if (value instanceof BigDecimal decimal) {
            schema.put(key, decimal);
        } else if (value instanceof String s) {
            schema.put(key, s);
        } else {
            throw new IllegalStateException("unsupported constraint keyword value type for '" + key + "': "
                    + (value == null ? "null" : value.getClass()));
        }
    }

    /**
     * The Java bean name a member joins Bean Validation metadata by: a field's own name, or the name a
     * getter implies by stripping its {@code get}/{@code is} prefix, or a setter implies by stripping
     * its {@code set}/{@code with} prefix.
     * A creator parameter has no such name here — {@link MetadataConstraintSource} joins it by
     * constructor and index instead, read from the {@link AnnotatedParameter} itself — so this method
     * returns {@code null} for one, which the metadata source's parameter branch never consults.
     */
    private static String javaBeanName(AnnotatedMember member) {
        Member raw = member.getMember();
        if (raw instanceof Field field) {
            return field.getName();
        }
        if (raw instanceof Method method) {
            boolean setterLike = method.getReturnType() == void.class || method.getParameterCount() > 0;
            return setterLike ? impliedFieldName(method) : getterBeanName(method);
        }
        return null;
    }

    /** The field a getter implies by its name: {@code getLevel}/{@code isActive} imply {@code level}/{@code active}. */
    private static String getterBeanName(Method method) {
        return stripAccessorPrefix(method.getName(), GETTER_PREFIXES);
    }

    /**
     * The Swagger metadata a creator parameter or setter carries; a field or getter keeps the module's
     * own handling.
     *
     * <p>{@code pattern}, {@code minLength}, {@code maxLength}, {@code minimum}/{@code exclusiveMinimum},
     * and {@code maximum}/{@code exclusiveMaximum} go through {@link #applyCorrection} — the same rule
     * the supplement's corrections follow (FR-009 of the rest-021 package: a constraint keyword is never
     * removed) — so {@code @Schema} metadata on an unscoped member never
     * loosens or drops a bound the always-active {@link WalkConstraintSource} floor already wrote from a
     * Bean Validation annotation on the same member: a differing pattern composes as an {@code allOf}
     * instead of replacing the floor's, and a bound keyword keeps whichever of the two values is
     * stricter. {@code description}, {@code title}, {@code format}, {@code enum} ({@code
     * allowableValues}), and {@code nullable} carry no constraint-source counterpart and keep the
     * pre-existing unconditional-overwrite behavior.
     */
    private static void translateSwagger(Schema swagger, ObjectNode schema) {
        if (!swagger.description().isEmpty()) {
            schema.put("description", swagger.description());
        }
        if (!swagger.title().isEmpty()) {
            schema.put("title", swagger.title());
        }
        if (!swagger.format().isEmpty()) {
            schema.put("format", swagger.format());
        }
        if (!swagger.pattern().isEmpty()) {
            applyCorrection(schema, "pattern", swagger.pattern());
        }
        if (swagger.minLength() != 0) {
            applyCorrection(schema, "minLength", swagger.minLength());
        }
        if (swagger.maxLength() != Integer.MAX_VALUE) {
            applyCorrection(schema, "maxLength", swagger.maxLength());
        }
        if (!swagger.minimum().isEmpty()) {
            applyCorrection(
                    schema,
                    swagger.exclusiveMinimum() ? "exclusiveMinimum" : "minimum",
                    new BigDecimal(swagger.minimum()));
        }
        if (!swagger.maximum().isEmpty()) {
            applyCorrection(
                    schema,
                    swagger.exclusiveMaximum() ? "exclusiveMaximum" : "maximum",
                    new BigDecimal(swagger.maximum()));
        }
        if (swagger.allowableValues().length > 0) {
            ArrayNode values = schema.putArray("enum");
            for (String value : swagger.allowableValues()) {
                values.add(value);
            }
        }
        if (swagger.nullable()) {
            JsonNode type = schema.get("type");
            if (type != null && type.isTextual()) {
                ArrayNode types = schema.putArray("type");
                types.add(type.textValue());
                types.add("null");
            }
        }
    }

    // ---------------------------------------------------------------- extras, aliases, reserved names

    /**
     * Publishes the conjunction of every any-setter's own extras beside the named properties (D004),
     * unless the class declares {@code @Schema(additionalProperties = FALSE)}, whose restriction the
     * Swagger module then publishes unopposed.
     *
     * <p>One any-setter renders byte-identically to before this task (the shared renderer's own plain
     * value schema, per {@link ValuePositionRenderer#renderConjunction}); more than one — the parent's
     * own plus every unwrapped sibling's own (D004) — renders their conjunction, {@code {"allOf": [...]}}
     * of each any-setter's own value schema, including its own type-use overlay. The key-count bound
     * (below) takes the strictest {@code @Size}/{@code @Schema(minProperties/maxProperties)} across
     * every any-setter in the set, never an {@code allOf} of {@code maxProperties} values.
     *
     * @return whether extras are described
     */
    private boolean describeExtras(
            ObjectNode definition,
            List<SettableAnyProperty> anySetters,
            Class<?> builtClass,
            SchemaGenerationContext context) {
        if (anySetters.isEmpty()) {
            return false;
        }
        Schema schema = builtClass.getAnnotation(Schema.class);
        if (schema != null && schema.additionalProperties() == Schema.AdditionalPropertiesValue.FALSE) {
            return false;
        }
        List<ValuePositionRenderer.ValuePosition> positions = new ArrayList<>(anySetters.size());
        List<AnnotatedMember> members = new ArrayList<>(anySetters.size());
        for (SettableAnyProperty anySetter : anySetters) {
            JavaType valueType = anySetter.getType();
            // rest-023 T003 (D001, N16/N9): the any-setter's own value position's AnnotatedType, read
            // off its own backing field/method's declared Map<K,V> type through the type-parameter
            // binding (ValuePositionRenderer.mapValueSlotOfMember), so a type-use constraint on V (N16)
            // — and, recursively, on a nested map's own V (N9) — overlays that any-setter's own value
            // schema through the shared renderer, exactly like a named member's own map position (T001
            // left this null; T003 wires the real AnnotatedType through, see ValuePositionRenderer's own
            // class Javadoc).
            AnnotatedMember member = anySetter.getProperty() == null
                    ? null
                    : anySetter.getProperty().getMember();
            AnnotatedType extrasAnnotatedType = member == null || member.getMember() == null
                    ? null
                    : ValuePositionRenderer.mapValueSlotOfMember(member.getMember());
            String memberName = member != null ? member.getName() : "extras";
            positions.add(new ValuePositionRenderer.ValuePosition(valueType, extrasAnnotatedType, memberName));
            members.add(member);
        }
        // An open position (valueType == null, or one of the renderer's own unconstrained value types)
        // writes an explicit empty additionalProperties object, exactly as before this extraction —
        // ValuePositionRenderer#renderConjunction returns null for the same reason renderValueSchema did.
        JsonNode valueSchema = valuePositionRenderer.renderConjunction(context, positions);
        if (valueSchema == null) {
            definition.putObject("additionalProperties");
        } else {
            definition.set("additionalProperties", valueSchema);
        }
        // A bound on an any-setter's own map itself — @Size on the field, @Schema(minProperties/
        // maxProperties) on the member — is a bound on the shared object's key count. D004: the
        // stricter @Size-derived value across every any-setter in the set is the constraint-source
        // floor, written first with a plain put(...); the Swagger value from any member is then routed
        // through applyCorrection (S1, the same rule the supplement's corrections follow — FR-009 of the
        // rest-021 package) so a looser @Schema(minProperties/maxProperties) never overwrites a stricter
        // bound already written for the same keyword — one keyword each on the object, never an allOf of
        // maxProperties/minProperties values.
        Integer maxProperties = null;
        Integer minProperties = null;
        for (AnnotatedMember member : members) {
            if (member == null) {
                continue;
            }
            Size size = member.getAnnotation(Size.class);
            if (size != null && member.getRawType() != null && Map.class.isAssignableFrom(member.getRawType())) {
                if (size.max() != Integer.MAX_VALUE) {
                    maxProperties = maxProperties == null ? size.max() : Math.min(maxProperties, size.max());
                }
                if (size.min() > 0) {
                    minProperties = minProperties == null ? size.min() : Math.max(minProperties, size.min());
                }
            }
        }
        if (maxProperties != null) {
            definition.put("maxProperties", maxProperties);
        }
        if (minProperties != null) {
            definition.put("minProperties", minProperties);
        }
        for (AnnotatedMember member : members) {
            if (member == null) {
                continue;
            }
            Schema swagger = member.getAnnotation(Schema.class);
            if (swagger != null) {
                if (swagger.maxProperties() != 0) {
                    applyCorrection(definition, "maxProperties", swagger.maxProperties());
                }
                if (swagger.minProperties() != 0) {
                    applyCorrection(definition, "minProperties", swagger.minProperties());
                }
            }
        }
        return true;
    }

    /**
     * The {@link ValuePositionRenderer.InlineComposer} supplied to {@link #valuePositionRenderer} at
     * construction (rest-023 T001, C6). Called by {@link ValuePositionRenderer#renderConjunction} (D004,
     * T004) for a conjunction position's own subschema whose value type is not profile-overridden (the
     * override carve-out is applied by the renderer itself, before this callback is ever reached), and
     * by D005's own member-level inline path (T005).
     *
     * <p>Mirrors {@link #describe}'s own bean classification, so a non-bean value type — a JDK/foreign
     * scalar, a {@code Map}-like type, a polymorphic base, or an opaque wrapper this class's own field
     * walk never had to protect — renders exactly as it does outside a conjunction: this method returns
     * {@code null}, and the renderer falls back to its own ordinary (non-inline) rendering for that
     * position. Only a value type that <em>does</em> resolve to a {@link BeanDeserializerBase} is
     * inlined; a type that looks bean-like yet resolves to a genuine custom deserializer is refused with
     * the same bounded diagnostic {@link #describe} throws for the same shape (F1).
     *
     * <p>D004, D005 § Recursion bound (security round-3 MEDIUM): the bean type is registered in {@link
     * #inlineInProgress} — a set distinct from {@link #inProgress} — for the duration of its own inline
     * population, and re-entry (from either set) is refused with a bounded diagnostic naming the member
     * and the remedy, never silently recursed and never left to the provider's own standard,
     * reflection-built {@code $defs} fallback (see {@link #provideCustomSchemaDefinition}'s own added
     * guard).
     *
     * @param beanType   the value type to inline, or render normally when it is not bean-like
     * @param memberName the any-setter's own member name, or D005's own member name, for the recursion
     *                   diagnostic
     * @param context    the active generation context
     * @return the inlined object (or scalar-creator) schema, or {@code null} when {@code beanType} is
     *     not bean-like and must be rendered by the renderer's own ordinary path instead
     * @throws JsonSchemaGenerationException when {@code beanType} resolves to a genuine custom
     *     deserializer (F1), or re-enters a type already being described inline or by reference (D005
     *     § Recursion bound)
     */
    private JsonNode inlineBeanSchema(JavaType beanType, String memberName, SchemaGenerationContext context) {
        Class<?> erased = beanType.getRawClass();
        // Mirrors provideCustomSchemaDefinition's own top-of-method exclusion (primitive/array/enum/
        // annotation, and every JDK/Jakarta/Jackson namespace) so a JDK or foreign scalar at a
        // conjunction position renders exactly as it does today, through the renderer's own ordinary
        // path — never through this bean-only inline machinery.
        if (erased.isPrimitive()
                || erased.isArray()
                || erased.isEnum()
                || erased.isAnnotation()
                || erased.getName().startsWith("java.")
                || erased.getName().startsWith("javax.")
                || erased.getName().startsWith("jakarta.")
                || erased.getName().startsWith("com.fasterxml.jackson.")) {
            return null;
        }
        JsonDeserializer<?> deserializer = unwrapDelegating(rootDeserializer(beanType));
        if (deserializer == null
                || deserializer instanceof AbstractDeserializer
                || deserializer instanceof MapDeserializer) {
            // A polymorphic base, or a Map-like value type: neither is a bean this method inlines;
            // rendered by the renderer's own ordinary path instead (a $ref/overlay for the former is
            // unreachable at this position since a conjunction value is never itself polymorphic here,
            // and a Map-like value renders through the renderer's own nested-map treatment).
            return null;
        }
        if (!(deserializer instanceof BeanDeserializerBase bean)) {
            boolean beanLike = BeanLikeTypes.beanLike(mapper, erased);
            if (!declaresOwnDeserializerOverride(beanType) && !beanLike) {
                return null;
            }
            throw refuseCustomDeserializer(beanType, deserializer, memberName);
        }
        // D005, T005: the registration/refusal/requireNotDelegating/scalar-creator/populateObjectSchema
        // tail is shared with the member-level inline-closure branch in propertySchema, rather than
        // re-implemented here — see inlineMemberSchema's own Javadoc. The renderer's own override carve-
        // out (this method's own class Javadoc) means the override-first check inside inlineMemberSchema
        // never actually fires from this call site; extrasSuppressed is always false here, since a
        // conjunction position's own extras suppression belongs to D004's own scope, not D005's.
        return inlineMemberSchema(beanType, bean, memberName, false, context);
    }

    /**
     * The shared inline-description tail (D005, T005) reused by both {@link #inlineBeanSchema} (D004's
     * own any-setter-conjunction composition) and the member-level case-insensitive/{@code FALSE} branch
     * in {@link #propertySchema}: the override-first check, the distinct {@link #inlineInProgress}
     * recursion bound (shared with {@link #inProgress}, refusing re-entry from either set), {@link
     * #requireNotDelegating}, the scalar-creator check, and {@link #populateObjectSchema} itself — see
     * {@code decisions/D005-...} § Recursion bound for the exact shape this method implements verbatim.
     *
     * <p>An override on {@code memberType} is handled differently depending on {@code extrasSuppressed}:
     * a plain case-insensitive-inline member (extras not suppressed) keeps its override's own {@code
     * $ref} exactly as before this task, signaled by a {@code null} return so the caller falls back to an
     * ordinary reference; a member whose {@code FALSE} annotation would suppress extras cannot honor that
     * annotation from an overridden value type at all, so generation refuses instead of silently dropping
     * either the override or the {@code FALSE}.
     *
     * @param memberType       the value type to describe inline
     * @param nestedBean       {@code memberType}'s own resolved bean deserializer
     * @param memberName       the member's own wire name (or the any-setter's own member name, for {@link
     *                         #inlineBeanSchema}'s own call), named in a recursion or override-precedence
     *                         diagnostic
     * @param extrasSuppressed whether the member's own extras are suppressed (D005): when {@code true},
     *                         a profile override on {@code memberType} is refused rather than kept as a
     *                         reference
     * @param context          the active generation context
     * @return the inline schema, or {@code null} when a non-extras-suppressed override applies and the
     *     caller must fall back to an ordinary reference instead
     * @throws JsonSchemaGenerationException when an extras-suppressed override applies, a delegating
     *     creator is reached, or {@code memberType} is already being described inline or by reference
     *     (D005 § Recursion bound)
     */
    private ObjectNode inlineMemberSchema(
            JavaType memberType,
            BeanDeserializerBase nestedBean,
            String memberName,
            boolean extrasSuppressed,
            SchemaGenerationContext context) {
        if (validatedProfile != null && validatedProfile.fragmentFor(memberType.getRawClass()) != null) {
            if (extrasSuppressed) {
                throw Diagnostics.failure(
                        "member \"" + memberName + "\" cannot honor additionalProperties = false: "
                                + Diagnostics.typeIdentity(memberType.getRawClass())
                                + " carries a profile override, which wins; declare the closure inside the"
                                + " override fragment instead of at the member",
                        null);
            }
            return null;
        }
        if (inProgress.contains(memberType) || !inlineInProgress.add(memberType)) {
            throw Diagnostics.failure(
                    "member \"" + memberName + "\" re-enters " + Diagnostics.typeIdentity(memberType.getRawClass())
                            + " inline; declare a JsonSchemaTypeOverride or close it at the type",
                    null);
        }
        try {
            ValueInstantiator instantiator = nestedBean.getValueInstantiator();
            requireNotDelegating(memberType, instantiator);
            ObjectNode inline = context.getGeneratorConfig().createObjectNode();
            // S2 (spike/deserializer-driven-schema round 4 ruling): describe()'s own root path checks
            // scalarCreator(instantiator) before ever building an object schema, so a from-string
            // scalar-creator type is described as {"type":"string"} (or "number"/"integer") wherever it
            // is referenced by $ref. This inline path applies the same check before populating an object
            // schema, mirroring describe()'s own branch, rather than describing a scalar-creator type
            // reached only through an inline position as an object.
            String scalar = scalarCreator(instantiator);
            if (scalar != null && !instantiator.canCreateFromObjectWith() && !instantiator.canCreateUsingDefault()) {
                inline.put("type", scalar);
                return inline;
            }
            populateObjectSchema(
                    inline,
                    memberType,
                    resolve(context, memberType),
                    nestedBean,
                    builderFor(memberType, nestedBean),
                    context,
                    nestedBean.isCaseInsensitive(),
                    extrasSuppressed);
            return inline;
        } finally {
            inlineInProgress.remove(memberType);
        }
    }

    /**
     * Every alias spelling declared on a bound member, keyed by the wire name of the property the
     * deserializer routes the spelling to, which is the property whose schema it is published with.
     * A spelling that is itself a bound name is not an alias of anything: the deserializer resolves
     * the exact name first.
     */
    private Map<String, List<String>> aliasPlan(
            BeanDeserializerBase bean, List<SettableBeanProperty> bound, Set<String> published) {
        Map<String, List<String>> plan = new TreeMap<>();
        Set<String> boundNames = new HashSet<>();
        bound.forEach(property -> boundNames.add(property.getName()));
        for (SettableBeanProperty property : bound) {
            AnnotatedMember member = property.getMember();
            if (member == null) {
                continue;
            }
            List<PropertyName> aliases = introspector().findPropertyAliases(member);
            if (aliases == null) {
                continue;
            }
            for (PropertyName alias : aliases) {
                String spelling = alias.getSimpleName();
                if (spelling == null || spelling.isEmpty() || boundNames.contains(spelling)) {
                    continue;
                }
                SettableBeanProperty claimant = bean.findProperty(spelling);
                if (claimant == null || !published.contains(claimant.getName())) {
                    continue;
                }
                List<String> spellings = plan.computeIfAbsent(claimant.getName(), key -> new ArrayList<>());
                if (!spellings.contains(spelling)) {
                    spellings.add(spelling);
                }
            }
        }
        return plan;
    }

    /**
     * The names to refuse beside described extras: every name the mapper's introspection knows for
     * the type that the document does not publish — an ignored name, a read-only name, a hidden
     * member the deserializer still binds, and an alias spelling of an unpublished property — so a
     * key by that name is rejected rather than accepted as an ordinary extra.
     */
    private Set<String> reservedNames(
            JavaType javaType,
            BeanDeserializerBase bean,
            List<SettableBeanProperty> bound,
            Set<String> published,
            Map<String, List<String>> aliasPlan) {
        Set<String> reserved =
                new TreeSet<>(ignoredNamesByType.computeIfAbsent(javaType, this::introspectUnboundNames));
        for (SettableBeanProperty property : bound) {
            if (!published.contains(property.getName())) {
                reserved.add(property.getName());
            }
            AnnotatedMember member = property.getMember();
            List<PropertyName> aliases = member == null ? null : introspector().findPropertyAliases(member);
            if (aliases == null) {
                continue;
            }
            for (PropertyName alias : aliases) {
                String spelling = alias.getSimpleName();
                if (spelling == null || spelling.isEmpty() || published.contains(spelling)) {
                    continue;
                }
                SettableBeanProperty claimant = bean.findProperty(spelling);
                boolean plannedForPublication = claimant != null
                        && aliasPlan.getOrDefault(claimant.getName(), List.of()).contains(spelling);
                if (!plannedForPublication) {
                    reserved.add(spelling);
                }
            }
        }
        reserved.removeAll(published);
        aliasPlan.values().forEach(reserved::removeAll);
        return reserved;
    }

    /**
     * The names the mapper's introspection knows for the type: every property it lists, whether the
     * deserializer binds it or not — an ignored name, a read-only name, a getter-only scalar — and
     * every name it ignores. The bound ones are subtracted by the caller once the document is known;
     * what is left is a name a client may recognize but must not send as an extra.
     */
    private Set<String> introspectUnboundNames(JavaType javaType) {
        BeanDescription description = introspection(javaType);
        Set<String> ignored = new TreeSet<>();
        // Properties first: the introspection collects lazily, and the ignored names are a by-product.
        for (BeanPropertyDefinition property : description.findProperties()) {
            ignored.add(property.getName());
        }
        ignored.addAll(description.getIgnoredPropertyNames());
        ignored.addAll(mapper.getDeserializationConfig()
                .getDefaultPropertyIgnorals(javaType.getRawClass(), description.getClassInfo())
                .findIgnoredForDeserialization());
        return Collections.unmodifiableSet(ignored);
    }

    private final Map<JavaType, BeanDescription> introspections = new ConcurrentHashMap<>();

    private BeanDescription introspection(JavaType javaType) {
        return introspections.computeIfAbsent(
                javaType, type -> mapper.getDeserializationConfig().introspect(type));
    }

    /**
     * The members that store what an any-accessor collects, which are never a property a client
     * sends: the field a method {@code @JsonAnyGetter} returns, which Jackson also fills under that
     * property's own name, and a creator parameter carrying {@code @JsonAnySetter}, whose name Jackson
     * consumes without binding it anywhere (measured: the key is silently dropped). Neither is
     * published, and both are reserved beside described extras. Identified by member, never by a name
     * an accessor implies. An {@link AnnotatedParameter} is kept as itself, because its
     * {@code getMember()} is the whole constructor.
     */
    private Set<Object> storageMembers(JavaType javaType, SettableAnyProperty anySetter) {
        Set<Object> storage = new HashSet<>();
        BeanDescription description = introspection(javaType);
        List<BeanPropertyDefinition> definitions = description.findProperties();
        AnnotatedMember anyGetter = description.findAnyGetter();
        if (anyGetter != null && anyGetter.getMember() != null) {
            storage.add(anyGetter.getMember());
            for (BeanPropertyDefinition definition : definitions) {
                AnnotatedMember getter = definition.getGetter();
                AnnotatedMember field = definition.getField();
                if (getter != null
                        && field != null
                        && getter.getMember().equals(anyGetter.getMember())
                        && anyGetter.getRawType().isAssignableFrom(field.getRawType())) {
                    storage.add(field.getMember());
                }
            }
        }
        if (anySetter != null && anySetter.getProperty() != null) {
            AnnotatedMember member = anySetter.getProperty().getMember();
            if (member instanceof AnnotatedParameter) {
                storage.add(member);
            }
        }
        return storage;
    }

    private static boolean isStorage(Set<Object> storage, AnnotatedMember member) {
        return member != null && (storage.contains(member) || storage.contains(member.getMember()));
    }

    // ---------------------------------------------------------------- nullability

    /**
     * The generator-private keyword marking a property schema whose member the library reports
     * nullable. A member schema asked from the library through its public entry is a reference
     * placeholder that is filled in only after generation, so nullability cannot be applied to it
     * here; {@link #applyNullability(JsonNode)} applies it to the finished document, the way the
     * library itself does for the members it walks, and removes the keyword.
     */
    static final String NULLABLE_MARKER = "x-vertique-nullable";

    /** The keywords beside which the library wraps a nullable schema in {@code anyOf} instead of extending its type. */
    private static final List<String> WRAPPING_KEYWORDS = List.of("$ref", "allOf", "anyOf", "oneOf", "const", "enum");

    /**
     * Marks a member schema nullable. The library decides the rendering when it applies nullability
     * — a composition is wrapped in {@code anyOf}, a plain type is extended with {@code "null"} — and
     * it decides before its final {@code allOf} cleanup; the shape at this moment is therefore
     * recorded with the mark, so the finished document renders exactly as the library's own walk
     * would.
     *
     * <p>Package-private (rest-023 T001, C6) so {@link ValuePositionRenderer}'s own nullability step
     * can share it once a later task (T002, {@code D002}) activates that step for an {@code
     * Optional}-typed value position; the two existing named-member callers here ({@code fieldSchema},
     * {@code methodSchema}) are unchanged by this task. {@link #NULLABLE_MARKER} and {@link
     * #WRAPPING_KEYWORDS} stay here, consulted only by this method and by {@link
     * #applyNullability(JsonNode)}, the generator's own document-level post-pass — neither moves.
     */
    static void markNullable(ObjectNode schema) {
        boolean wrap = WRAPPING_KEYWORDS.stream().anyMatch(schema::has);
        schema.put(NULLABLE_MARKER, wrap ? "wrap" : "extend");
    }

    /**
     * Applies every nullability mark in a finished document: a schema with a single {@code type}
     * gains {@code "null"} beside it, one with several gains it unless present, and one with no
     * {@code type} of its own — a reference or a composition — is wrapped in {@code anyOf} with the
     * null schema, which is the library's own rendering of a nullable member.
     *
     * @param document the generated document, before alias expansion
     */
    static void applyNullability(JsonNode document) {
        AnnotationJsonSchemaGenerator.AliasExpansion.walkSchemaPositions(document, schema -> {
            JsonNode marker = schema.remove(NULLABLE_MARKER);
            if (marker == null) {
                return;
            }
            boolean wrap =
                    "wrap".equals(marker.asText()) || WRAPPING_KEYWORDS.stream().anyMatch(schema::has);
            JsonNode type = schema.get("type");
            if (!wrap && type != null && type.isTextual()) {
                if (!"null".equals(type.textValue())) {
                    ArrayNode types = JsonNodeFactory.instance.arrayNode();
                    types.add(type.textValue());
                    types.add("null");
                    schema.set("type", types);
                }
                return;
            }
            if (!wrap && type != null && type.isArray()) {
                for (JsonNode entry : type) {
                    if ("null".equals(entry.textValue())) {
                        return;
                    }
                }
                ((ArrayNode) type).add("null");
                return;
            }
            ObjectNode nullSchema = JsonNodeFactory.instance.objectNode();
            nullSchema.put("type", "null");
            JsonNode existing = schema.get("anyOf");
            if (existing != null && existing.isArray()) {
                for (JsonNode branch : existing) {
                    if (nullSchema.equals(branch)) {
                        return;
                    }
                }
            }
            ObjectNode wrapped = JsonNodeFactory.instance.objectNode();
            wrapped.setAll(schema);
            schema.removeAll();
            schema.putArray("anyOf").add(nullSchema).add(wrapped);
        });
    }

    // ---------------------------------------------------------------- refusals

    private static void requireCaseSensitive(BeanDeserializerBase bean, Class<?> type, String what) {
        if (bean.isCaseInsensitive()) {
            throw Diagnostics.failure(
                    "JSON Schema generation failed for " + Diagnostics.typeIdentity(type) + ": " + what
                            + " is bound case-insensitively (MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES or"
                            + " @JsonFormat(with = ACCEPT_CASE_INSENSITIVE_PROPERTIES)), which a schema's properties"
                            + " cannot describe; bind it case-sensitively, or declare a JsonSchemaTypeOverride for the"
                            + " type on the profile",
                    null);
        }
    }

    /**
     * Refuses an unwrapped member on an any-setter type whose own value type declares a nested
     * {@code @JsonUnwrapped} member of its own (F2, security review round 1, HIGH).
     *
     * <p>{@link #foldUnwrappedChildIntoParentPlan} folds one unwrapped child's alias spellings and
     * hidden/ignored names into the parent's own alias plan and reserved-name set, so a key that would
     * otherwise bind through the extras bucket unconstrained is instead published or refused. That fold
     * reads the child's own introspection and one-level {@link #boundProperties} list directly; it does
     * not itself descend into a grandchild's own unwrapped member, so a grandchild's alias or hidden
     * member would still bind through the extras bucket unconstrained if this method did not refuse the
     * combination outright — the same soundness posture {@link #requireCaseSensitive} already takes for
     * a case-insensitive unwrapped member, applied here instead of an unsound two-level fold.
     *
     * @param childBuilder the unwrapped child's own captured builder, or {@code null} when it declares
     *                     no builder-visible property at all (nothing to descend into, so nothing to
     *                     refuse)
     * @param childClass   the unwrapped child's own raw type, named in the diagnostic
     * @param parentMember the parent's member name carrying the unwrapped child, named in the diagnostic
     * @throws JsonSchemaGenerationException when {@code childBuilder} declares a nested unwrapped member
     */
    private void requireNoNestedUnwrapping(
            BeanDeserializerBuilder childBuilder, Class<?> childClass, String parentMember) {
        if (childBuilder == null) {
            return;
        }
        Iterator<SettableBeanProperty> it = childBuilder.getProperties();
        while (it.hasNext()) {
            SettableBeanProperty grandchildProperty = it.next();
            if (introspector().findUnwrappingNameTransformer(grandchildProperty.getMember()) != null) {
                throw Diagnostics.failure(
                        "JSON Schema generation failed for " + Diagnostics.typeIdentity(childClass)
                                + ": the unwrapped member \""
                                + Diagnostics.truncate(parentMember, Diagnostics.MAX_SHORT_IDENTITY_LENGTH)
                                + "\" is itself bound on an any-setter type and declares a nested @JsonUnwrapped"
                                + " member of its own, which this generator cannot fold soundly two levels deep;"
                                + " declare a JsonSchemaTypeOverride for the type on the profile, or flatten the"
                                + " nested unwrap by hand",
                        null);
            }
        }
    }

    /**
     * Folds one unwrapped child's alias spellings and hidden/ignored names into the parent's own alias
     * plan and reserved-name seed (F2, security review round 1, HIGH): before this fold, {@code
     * aliasPlan} and {@code reservedNames} read only the parent's own {@link #boundProperties} list,
     * which {@link #boundProperties(BeanDeserializerBase, BeanDeserializerBuilder)} builds
     * <em>excluding</em> unwrapped members, and the parent's own introspection, which never reaches a
     * member of the unwrapped child's declaring class at all. A client-submitted key spelling an
     * unwrapped child's {@code @JsonAlias}, or naming its {@code @Schema(hidden = true)} or {@code
     * @JsonIgnore} member, therefore bound through the extras bucket unconstrained: neither published
     * (so not validated against the real member's own constraint) nor reserved (so not refused either).
     *
     * <p>Every name this method contributes is the child's own <em>local</em> spelling with {@code
     * transformer}'s unwrapping prefix or suffix applied by hand — the actual wire spelling the binder
     * reads — since only a bound child property's own name (already read from the transformed {@code
     * unwrapped} deserializer by the caller) already carries that transform; an alias declared directly
     * on the child's raw member, and a name this method reads through the child's own untransformed
     * {@link #introspectUnboundNames}, do not.
     *
     * @param transformer          the unwrapping name transformer Jackson resolved for this child
     * @param childClass           the unwrapped child's own raw type
     * @param childBound           the child's own bound properties, read from the transformed deserializer
     * @param published            the names published so far, parent and every prior child included
     * @param aliasPlanTarget      the parent's own alias plan, folded into in place
     * @param reservedSeedTarget   the parent's own reserved-name seed, folded into in place
     */
    private void foldUnwrappedChildIntoParentPlan(
            NameTransformer transformer,
            Class<?> childClass,
            List<SettableBeanProperty> childBound,
            Set<String> published,
            Map<String, List<String>> aliasPlanTarget,
            Set<String> reservedSeedTarget) {
        // S1 (spike/deserializer-driven-schema round 4 ruling): alias folding is skipped outright for a
        // non-no-op unwrap transformer (a declared @JsonUnwrapped prefix or suffix) — measured: neither
        // the alias's own plain local spelling nor the hand-transformed one actually binds through
        // Jackson's own unwrapping deserializer once a real prefix or suffix is in play, so folding
        // either one into the plan would publish and validate a spelling the binder never reaches under
        // this member. Reservation of an unpublished or hidden child name (below) is unaffected: that
        // name still comes from the already-transformed bound-property list or from
        // introspectUnboundNames plus a correct hand transform, neither of which shares the alias fold's
        // own unsoundness.
        boolean noOpTransformer = transformer == NameTransformer.NOP;
        for (SettableBeanProperty childProperty : childBound) {
            String claimant = childProperty.getName();
            if (!published.contains(claimant)) {
                // Bound by the child but not published here: a name a sibling already claimed, or one
                // {@link #propertySchema} hid on purpose (@Schema(hidden = true)) — either way, reserved
                // rather than left to fall through to the extras bucket unconstrained.
                reservedSeedTarget.add(claimant);
                continue;
            }
            if (!noOpTransformer) {
                continue;
            }
            AnnotatedMember member = childProperty.getMember();
            if (member == null) {
                continue;
            }
            List<PropertyName> aliases = introspector().findPropertyAliases(member);
            if (aliases == null) {
                continue;
            }
            for (PropertyName alias : aliases) {
                String localSpelling = alias.getSimpleName();
                if (localSpelling == null || localSpelling.isEmpty()) {
                    continue;
                }
                String wireSpelling = transformer.transform(localSpelling);
                if (wireSpelling.isEmpty() || published.contains(wireSpelling)) {
                    continue;
                }
                List<String> spellings = aliasPlanTarget.computeIfAbsent(claimant, key -> new ArrayList<>());
                if (!spellings.contains(wireSpelling)) {
                    spellings.add(wireSpelling);
                }
            }
        }
        for (String localUnboundName :
                introspectUnboundNames(mapper.getTypeFactory().constructType(childClass))) {
            String wireName = transformer.transform(localUnboundName);
            if (!wireName.isEmpty() && !published.contains(wireName)) {
                reservedSeedTarget.add(wireName);
            }
        }
    }

    private static String scalarCreator(ValueInstantiator instantiator) {
        if (instantiator.canCreateFromInt()
                || instantiator.canCreateFromLong()
                || instantiator.canCreateFromBigInteger()) {
            return "integer";
        }
        if (instantiator.canCreateFromDouble() || instantiator.canCreateFromBigDecimal()) {
            return "number";
        }
        if (instantiator.canCreateFromString()) {
            return "string";
        }
        if (instantiator.canCreateFromBoolean()) {
            return "boolean";
        }
        return null;
    }

    // ---------------------------------------------------------------- creator parameters with no member

    /**
     * Whether a creator parameter's wire name has no member to publish it faithfully with: no field of
     * the same wire name to join to (Jackson's own statement that field and parameter are one logical
     * property), no getter- or setter-derived property of the same name either, and no constraint
     * annotation of its own.
     *
     * <p>Publishing such a parameter would either invent a property main's field walk never had, or
     * silently drop a constraint written on a member the join cannot reach — the type is a closed
     * boundary either way, and this gap is the one that let a constrained field's limit be bypassed
     * through its renamed creator parameter (design proof CR1c). The caller excludes the property from
     * publication and, on an any-setter type, from the reserved-name set as well: main described no
     * property by this name and reserved none either, since it has nothing to key a refusal on.
     *
     * @param property   the bound property to test
     * @param builtClass the type being described
     * @return whether the property must be excluded entirely
     */
    private static boolean isUnjoinableConstraintFreeCreatorParameter(
            SettableBeanProperty property, Class<?> builtClass) {
        AnnotatedMember member = property.getMember();
        if (!(member instanceof AnnotatedParameter parameter)) {
            return false;
        }
        if (backingField(builtClass, parameter, property.getName()) != null) {
            return false;
        }
        if (carriesConstraintAnnotation(parameter)) {
            return false;
        }
        return !hasAccessorDerivedProperty(builtClass, property.getName());
    }

    /** Whether a member carries any of the constraint annotations {@link #translateConstraints} reads. */
    private static boolean carriesConstraintAnnotation(AnnotatedMember member) {
        return member.getAnnotation(Max.class) != null
                || member.getAnnotation(Min.class) != null
                || member.getAnnotation(DecimalMax.class) != null
                || member.getAnnotation(DecimalMin.class) != null
                || member.getAnnotation(Size.class) != null
                || member.getAnnotation(Pattern.class) != null
                || member.getAnnotation(NotBlank.class) != null
                || member.getAnnotation(NotEmpty.class) != null
                || member.getAnnotation(NotNull.class) != null;
    }

    /** Whether a getter, setter, or builder-style {@code with} method elsewhere implies the wire name. */
    private static boolean hasAccessorDerivedProperty(Class<?> builtClass, String wireName) {
        for (Class<?> current = builtClass;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) {
                    continue;
                }
                boolean getter = method.getParameterCount() == 0 && method.getReturnType() != void.class;
                boolean setter = method.getParameterCount() == 1;
                if ((getter || setter) && impliedAccessorName(method).equals(wireName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The property name a getter, setter, or builder-style {@code with} method implies from its own name. */
    private static String impliedAccessorName(Method method) {
        return stripAccessorPrefix(method.getName(), ACCESSOR_PREFIXES);
    }

    /** Prefixes {@link #impliedFieldName} strips: a setter or builder-style {@code with} method. */
    private static final List<String> SETTER_PREFIXES = List.of("set", "with");

    /** Prefixes {@link #getterBeanName} strips: a getter. */
    private static final List<String> GETTER_PREFIXES = List.of("get", "is");

    /** Prefixes {@link #impliedAccessorName} strips: any getter, setter, or builder-style {@code with} method. */
    private static final List<String> ACCESSOR_PREFIXES = List.of("get", "set", "with", "is");

    /**
     * The single accessor-prefix-stripping helper (S4), used everywhere a method name is reduced to
     * the property name it implies: {@link #impliedFieldName}, {@link #getterBeanName}, and
     * {@link #impliedAccessorName} each call this with their own prefix list.
     *
     * <p>A prefix is stripped only when the character immediately following it is uppercase — the
     * JavaBean convention a real accessor follows ({@code getName} implies {@code name}) and an
     * ordinary method that merely starts with the same letters does not ({@code issue} is not {@code
     * is} + {@code sue}; {@code settle} is not {@code set} + {@code tle}). Without this check, an
     * ordinary method whose name happens to start with a prefix is silently mistaken for an accessor
     * of a property that does not exist, which can wrongly join or exclude an unrelated member.
     *
     * @param name     the method's own name
     * @param prefixes the accessor prefixes to try, in order
     * @return the implied property name, or {@code name} unchanged when no prefix qualifies
     */
    private static String stripAccessorPrefix(String name, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.length() > prefix.length()
                    && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                String rest = name.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return name;
    }

    // ---------------------------------------------------------------- case-insensitive folding

    /** The regular-expression metacharacters {@link #asciiFoldPattern} escapes in a literal segment. */
    private static final String REGEX_METACHARACTERS = ".^$|?*+()[]{}\\";

    /**
     * An ASCII case-folding regular expression for a property name, anchored at both ends: each ASCII
     * letter becomes a two-character class of its lower- and upper-case form — {@code name} folds to
     * {@code ^[nN][aA][mM][eE]\z} — and every other character is escaped literally.
     *
     * <p>Anchored with {@code \z} rather than {@code $} (S2): {@code io.vertx.json.schema} 5.1.6
     * compiles the {@code pattern} keyword with plain {@code java.util.regex.Pattern} (see {@code
     * PatternFlagRenderingTest}), whose {@code $} — without {@code Pattern.MULTILINE} — still matches
     * immediately before a single trailing line terminator, not only at the true end of input. A key
     * ending in a newline would therefore wrongly match this fold under {@code $}; {@code \z} matches
     * only the absolute end of the input, with no such exception.
     *
     * <p>The fold is ASCII-only by design, not a locale-aware one: {@code String#toLowerCase()} and
     * {@code String#toUpperCase()} without an explicit {@link java.util.Locale} — which is what Jackson
     * itself measurably uses for its own case-insensitive property lookup — fold differently under a
     * non-root default locale (the Turkish {@code I}/{@code ı}/{@code İ}/{@code i} pairing is the
     * classic case), so a schema fold pinned to the JVM's default locale would silently drift from the
     * binder's under a locale change. Anchoring on the fixed ASCII pairing keeps the schema's fold
     * independent of the server's default locale entirely, at the cost of refusing a name the fold does
     * not cover.
     *
     * @param name the property's canonical wire name
     * @return the anchored pattern, or {@code null} when the name is empty or carries a non-ASCII
     *     letter, which this fold does not cover
     */
    private static String asciiFoldPattern(String name) {
        if (name.isEmpty()) {
            return null;
        }
        StringBuilder pattern = new StringBuilder(name.length() * 4 + 2).append('^');
        for (int index = 0; index < name.length(); index++) {
            char letter = name.charAt(index);
            if (letter > 0x7E) {
                return null;
            }
            if ((letter >= 'a' && letter <= 'z') || (letter >= 'A' && letter <= 'Z')) {
                pattern.append('[')
                        .append(Character.toLowerCase(letter))
                        .append(Character.toUpperCase(letter))
                        .append(']');
            } else if (Character.isLetter(letter)) {
                // No ASCII code point besides a-zA-Z is itself a letter; fail closed rather than emit
                // an unfolded literal for one this fold was not designed to reach.
                return null;
            } else {
                if (REGEX_METACHARACTERS.indexOf(letter) >= 0) {
                    pattern.append('\\');
                }
                pattern.append(letter);
            }
        }
        return pattern.append("\\z").toString();
    }

    /**
     * Publishes a bound property's schema a second time under {@code patternProperties}, keyed by its
     * ASCII case-folding pattern, for a case-insensitively bound type — beside the canonical-name entry
     * already published under {@code properties}.
     *
     * @param definition               the object schema being built
     * @param patternPropertiesHolder  a one-element holder for the lazily created {@code
     *     patternProperties} object, shared across every call for the same definition
     * @param name                     the property's canonical wire name
     * @param schema                   the property's already-built schema
     * @param type                     the type being described, for the diagnostic
     * @throws JsonSchemaGenerationException when the name carries a non-ASCII letter this fold does not
     *     cover
     */
    private static void publishFolded(
            ObjectNode definition, ObjectNode[] patternPropertiesHolder, String name, JsonNode schema, Class<?> type) {
        String pattern = asciiFoldPattern(name);
        if (pattern == null) {
            throw Diagnostics.failure(
                    "JSON Schema generation failed for " + Diagnostics.typeIdentity(type)
                            + ": the case-insensitively bound property \""
                            + Diagnostics.truncate(name, Diagnostics.MAX_SHORT_IDENTITY_LENGTH)
                            + "\" carries a non-ASCII letter, which this generator's ASCII case folding"
                            + " does not cover; declare a JsonSchemaTypeOverride for the type on the"
                            + " profile, or bind it case-sensitively",
                    null);
        }
        if (patternPropertiesHolder[0] == null) {
            patternPropertiesHolder[0] = definition.putObject("patternProperties");
        }
        patternPropertiesHolder[0].set(pattern, schema.deepCopy());
    }

    /**
     * One combined ASCII case-folding pattern excluding every reserved name, for {@code
     * propertyNames: {"not": {"pattern": ...}}} on a case-insensitively bound type.
     *
     * @param names the reserved names to fold together
     * @param type  the type being described, for the diagnostic
     * @return the combined, anchored alternation pattern
     * @throws JsonSchemaGenerationException when a name carries a non-ASCII letter this fold does not
     *     cover
     */
    private static String combinedFoldPattern(Set<String> names, Class<?> type) {
        List<String> alternatives = new ArrayList<>();
        for (String name : names) {
            String pattern = asciiFoldPattern(name);
            if (pattern == null) {
                throw Diagnostics.failure(
                        "JSON Schema generation failed for " + Diagnostics.typeIdentity(type)
                                + ": the case-insensitively bound reserved name \""
                                + Diagnostics.truncate(name, Diagnostics.MAX_SHORT_IDENTITY_LENGTH)
                                + "\" carries a non-ASCII letter, which this generator's ASCII case folding"
                                + " does not cover; declare a JsonSchemaTypeOverride for the type on the"
                                + " profile, or bind it case-sensitively",
                        null);
            }
            // Strip the per-name anchors: every alternative shares one pair of anchors around the group.
            // The leading anchor is the single character '^'; the trailing one is the two-character
            // "\z" (S2), not "$".
            alternatives.add(pattern.substring(1, pattern.length() - 2));
        }
        return "^(?:" + String.join("|", alternatives) + ")\\z";
    }

    // ---------------------------------------------------------------- Jackson plumbing, public API only

    /**
     * The deserializer the profile's mapper resolves for a type, contextualized as at bind time, or
     * {@code null} when the mapper has none.
     *
     * @throws JsonSchemaGenerationException when the mapper cannot build one, which is a type the
     *                                        binder rejects on every request
     */
    private JsonDeserializer<?> rootDeserializer(JavaType type) {
        DefaultDeserializationContext blueprint = (DefaultDeserializationContext) mapper.getDeserializationContext();
        try (JsonParser parser = mapper.createParser("{}")) {
            DefaultDeserializationContext context =
                    blueprint.createInstance(mapper.getDeserializationConfig(), parser, mapper.getInjectableValues());
            JsonDeserializer<Object> root = context.findRootValueDeserializer(type);
            if (root instanceof TypeWrappedDeserializer) {
                // The wrapper exposes no accessor for what it wraps: the non-contextual lookup plus
                // explicit contextualization reaches the same deserializer bind time uses.
                JsonDeserializer<Object> inner = context.findNonContextualValueDeserializer(type);
                return context.handleSecondaryContextualization(inner, null, type);
            }
            return root;
        } catch (JsonSchemaGenerationException refused) {
            throw refused;
        } catch (Exception | LinkageError failed) {
            throw Diagnostics.failure(
                    "JSON Schema generation failed for " + Diagnostics.typeIdentity(type.getRawClass())
                            + ": the profile's mapper cannot deserialize it",
                    failed);
        }
    }

    private static JsonDeserializer<?> unwrap(JsonDeserializer<?> deserializer) {
        JsonDeserializer<?> current = deserializer;
        while (current instanceof TypeWrappedDeserializer wrapped && wrapped.getDelegatee() != null) {
            current = wrapped.getDelegatee();
        }
        return current;
    }

    /**
     * Unwraps a {@link DelegatingDeserializer} chain down to its ultimate delegate (W2,
     * spike/deserializer-driven-schema round 4 ruling), the root-level counterpart of {@link
     * #unwrap(JsonDeserializer)}'s own {@code TypeWrappedDeserializer} unwrap: a mapper-wide {@code
     * BeanDeserializerModifier} that wraps every bean deserializer in a forwarding {@code
     * DelegatingDeserializer} subclass still ends up calling the wrapped bean deserializer at bind time
     * through {@code getDelegatee()}, so a caller deciding bean-ness must decide it from the delegate
     * rather than from the forwarding wrapper itself. {@code null} or self-referential is treated the
     * same as "nothing further to unwrap", never looping.
     *
     * <p>Bounded to a <em>pure</em> forwarder (W-1, round 5 review finding): unwrapping stops at the
     * first {@code DelegatingDeserializer} whose own class overrides any of {@code
     * deserialize(JsonParser, DeserializationContext)}, {@code deserialize(JsonParser,
     * DeserializationContext, Object)}, or {@code deserializeWithType(...)} — see {@link
     * #overridesDelegatingDeserializerMethods}. Such an override can read from the parser itself before,
     * or instead of, ever reaching the delegate, so the wire shape it actually accepts is not provably
     * the delegate's own; unwrapping straight through it would silently describe a shape the type does
     * not accept. The un-unwrapped wrapper is then classified — and, for a bean-like type, refused — by
     * the same rule a genuine type-level deserializer override is refused by (F1's posture), since a
     * subclass overriding one of these methods is a replaced deserializer in every sense that matters
     * here. Every call site that needs this bound gets it from this one method.
     */
    private static JsonDeserializer<?> unwrapDelegating(JsonDeserializer<?> deserializer) {
        JsonDeserializer<?> current = deserializer;
        while (current instanceof DelegatingDeserializer delegating
                && !overridesDelegatingDeserializerMethods(delegating.getClass())) {
            JsonDeserializer<?> delegatee = delegating.getDelegatee();
            if (delegatee == null || delegatee == current) {
                break;
            }
            current = delegatee;
        }
        return current;
    }

    /**
     * Whether {@code deserializerClass} — a concrete {@link DelegatingDeserializer} subclass — declares
     * its own override of at least one of the three methods {@link DelegatingDeserializer} itself
     * implements as pure forwarding to its delegate: {@code deserialize(JsonParser,
     * DeserializationContext)}, {@code deserialize(JsonParser, DeserializationContext, Object)}, and
     * {@code deserializeWithType(JsonParser, DeserializationContext, TypeDeserializer)} (W-1, round 5
     * review finding). {@code DelegatingDeserializer} remains every one of the three methods' declaring
     * class exactly when the subclass changes none of them, which is the only shape {@link
     * #unwrapDelegating} may safely unwrap straight through.
     *
     * @param deserializerClass the concrete {@code DelegatingDeserializer} subclass to inspect
     * @return {@code true} when the class overrides at least one of the three methods itself
     */
    private static boolean overridesDelegatingDeserializerMethods(Class<?> deserializerClass) {
        return declaresOwnMethod(deserializerClass, "deserialize", JsonParser.class, DeserializationContext.class)
                || declaresOwnMethod(
                        deserializerClass, "deserialize", JsonParser.class, DeserializationContext.class, Object.class)
                || declaresOwnMethod(
                        deserializerClass,
                        "deserializeWithType",
                        JsonParser.class,
                        DeserializationContext.class,
                        TypeDeserializer.class);
    }

    /**
     * Whether the given method, as resolved on {@code type}, is declared by a class other than {@link
     * DelegatingDeserializer} — i.e. some subclass between {@code type} and {@code DelegatingDeserializer}
     * overrides it. Every {@code DelegatingDeserializer} subclass inherits all three methods {@link
     * #overridesDelegatingDeserializerMethods} inspects, so a lookup miss never occurs for the methods
     * this is used for; it is still treated as "not overridden" rather than propagating the checked
     * exception, since a miss here would mean a shape this hierarchy does not have.
     */
    private static boolean declaresOwnMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes).getDeclaringClass() != DelegatingDeserializer.class;
        } catch (NoSuchMethodException impossible) {
            return false;
        }
    }

    /**
     * The builder Jackson assembled the type's deserializer from, captured through a
     * {@code BeanDeserializerModifier} on a copy of the profile's mapper, since the resolved
     * deserializer exposes neither its any-setter nor its unwrapped members.
     */
    private BeanDeserializerBuilder builderFor(JavaType type, BeanDeserializerBase bean) {
        JavaType key = bean instanceof BuilderBasedDeserializer ? mapper.constructType(bean.getBeanClass()) : type;
        BeanDeserializerBuilder builder = builders.get(key);
        if (builder == null) {
            try {
                // S1: mapper.copy() (and the capturing module's own setup) now runs inside this guarded
                // block too, alongside the deserializer resolution that follows it — a mapper subclass
                // that refuses to copy itself (overrides copy() to throw) previously escaped as a raw
                // exception straight out of this method instead of the bounded generation diagnostic
                // every other failure here produces.
                ObjectMapper copy = capturing;
                if (copy == null) {
                    copy = mapper.copy();
                    SimpleModule module = new SimpleModule("vertique-json-schema-capture");
                    module.setDeserializerModifier(new BeanDeserializerModifier() {
                        @Override
                        public BeanDeserializerBuilder updateBuilder(
                                DeserializationConfig config,
                                BeanDescription description,
                                BeanDeserializerBuilder captured) {
                            builders.put(description.getType(), captured);
                            return captured;
                        }
                    });
                    copy.registerModule(module);
                    capturing = copy;
                }
                DefaultDeserializationContext blueprint =
                        (DefaultDeserializationContext) copy.getDeserializationContext();
                try (JsonParser parser = copy.createParser("{}")) {
                    blueprint
                            .createInstance(copy.getDeserializationConfig(), parser, copy.getInjectableValues())
                            .findRootValueDeserializer(type);
                }
            } catch (Exception failed) {
                throw Diagnostics.failure(
                        "JSON Schema generation failed for " + Diagnostics.typeIdentity(type.getRawClass())
                                + ": the profile's mapper cannot deserialize it",
                        failed);
            }
            builder = builders.get(key);
        }
        return builder;
    }

    private AnnotationIntrospector introspector() {
        return mapper.getDeserializationConfig().getAnnotationIntrospector();
    }

    private static ResolvedTypeWithMembers membersOf(
            TypeContext typeContext, ResolvedType resolved, Class<?> declaring) {
        if (declaring.isAssignableFrom(resolved.getErasedType())) {
            return typeContext.resolveWithMembers(resolved);
        }
        return typeContext.resolveWithMembers(typeContext.resolve(declaring));
    }

    /**
     * Resolves a Jackson-resolved type into the schema library's type model.
     *
     * <p>Package-private (rest-023 T001, C6) so {@link ValuePositionRenderer#renderValueSchema} can
     * share it rather than duplicate it; every other call site in this class is unchanged by this task.
     */
    static ResolvedType resolve(SchemaGenerationContext context, JavaType type) {
        return context.getTypeContext().resolve(toResolvedType(type));
    }

    /** A Jackson-resolved type carried over to the schema library's type model, arguments included. */
    private static ResolvedType toResolvedType(JavaType type) {
        if (type.isArrayType()) {
            return CLASSMATE.arrayType(toResolvedType(type.getContentType()));
        }
        List<JavaType> bound = type.getBindings().getTypeParameters();
        ResolvedType[] arguments = new ResolvedType[bound.size()];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = toResolvedType(bound.get(i));
        }
        return CLASSMATE.resolve(type.getRawClass(), arguments);
    }

    /** The schema library's resolved type carried over to Jackson's, arguments included. */
    private JavaType toJavaType(ResolvedType resolved) {
        TypeFactory factory = mapper.getTypeFactory();
        if (resolved.isArray()) {
            return factory.constructArrayType(toJavaType(resolved.getArrayElementType()));
        }
        List<ResolvedType> parameters = resolved.getTypeParameters();
        if (parameters.isEmpty()) {
            return factory.constructType((Type) resolved.getErasedType());
        }
        JavaType[] arguments = new JavaType[parameters.size()];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = toJavaType(parameters.get(i));
        }
        return factory.constructParametricType(resolved.getErasedType(), arguments);
    }
}
