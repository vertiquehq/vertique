// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.ResolvedTypeWithMembers;
import com.fasterxml.classmate.TypeResolver;
import com.fasterxml.classmate.members.ResolvedField;
import com.fasterxml.classmate.members.ResolvedMethod;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
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
import com.fasterxml.jackson.databind.deser.std.MapDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDelegatingDeserializer;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * same-named field's and getter's annotations. A builder method carries no constraint of its own:
 * its constraints are borrowed from the built type's field of the same name.
 *
 * <p>Two mechanisms are detected and refused rather than described, because a document describing
 * them would be false: a type whose deserializer is not a bean deserializer (a type-level
 * {@code @JsonDeserialize(using = ...)} or a module-registered deserializer) unless the profile
 * declares a schema override for it, and a type bound case-insensitively through the mapper, a
 * class-level or a member-level {@code @JsonFormat}. Both fail generation with a bounded diagnostic
 * naming the type and the remedy.
 *
 * <p>Registered after the annotation modules on purpose: the Jackson module's subtype resolver keeps
 * precedence for a {@code @JsonTypeInfo} root and consults this provider for each concrete subtype;
 * the profile override provider, registered before the modules, keeps precedence for an overridden
 * class, which is why an overridden custom-deserialized type is never refused here.
 */
final class InputPropertyDescriber implements CustomDefinitionProviderV2 {

    /** Value types that accept every JSON value, and are therefore published as the empty schema. */
    private static final Set<Class<?>> UNCONSTRAINED_VALUE_TYPES = Set.of(Object.class, JsonNode.class, TreeNode.class);

    /** Resolves a Jackson-resolved type into the schema library's type model. */
    private static final TypeResolver CLASSMATE = new TypeResolver();

    private final ObjectMapper mapper;
    private final boolean strictSpellings;
    private final ConstraintSource constraintSource;

    /** The introspected ignored names per type, the one fact the deserializer does not carry. */
    private final Map<JavaType, Set<String>> ignoredNamesByType = new ConcurrentHashMap<>();

    /** The builders Jackson used, captured on a copy of the mapper; keyed by the type they built for. */
    private final Map<JavaType, BeanDeserializerBuilder> builders = new ConcurrentHashMap<>();

    private volatile ObjectMapper capturing;

    /** The types being described on the current generation path, to bound recursion. */
    private final Set<JavaType> inProgress = new HashSet<>();

    /**
     * @param mapper          the profile's mapper, which the binder parses a body with
     * @param strictSpellings whether the profile forbids several spellings of one property
     */
    InputPropertyDescriber(ObjectMapper mapper, boolean strictSpellings) {
        this(mapper, strictSpellings, WalkConstraintSource.INSTANCE);
    }

    /**
     * @param mapper           the profile's mapper, which the binder parses a body with
     * @param strictSpellings  whether the profile forbids several spellings of one property
     * @param constraintSource the source of value-schema constraints for every bound property; {@link
     *                         WalkConstraintSource#INSTANCE} unless the generator was built with a
     *                         {@link jakarta.validation.Validator}
     */
    InputPropertyDescriber(ObjectMapper mapper, boolean strictSpellings, ConstraintSource constraintSource) {
        this.mapper = mapper;
        this.strictSpellings = strictSpellings;
        this.constraintSource = constraintSource;
    }

    /** Clears the per-generation recursion state after an abnormal exit. */
    void resetAfterAbortedGeneration() {
        inProgress.clear();
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
        JavaType javaType = toJavaType(resolved);
        if (javaType.isEnumType() || javaType.isReferenceType() || javaType.isCollectionLikeType()) {
            return null;
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
        JsonDeserializer<?> deserializer = rootDeserializer(javaType);
        if (deserializer == null || deserializer instanceof AbstractDeserializer) {
            // A polymorphic base: the Jackson module's subtype resolver owns it.
            return null;
        }
        if (deserializer instanceof MapDeserializer) {
            return describeMapLike(javaType, context);
        }
        if (!(deserializer instanceof BeanDeserializerBase bean)) {
            throw Diagnostics.failure(
                    "JSON Schema generation failed for " + Diagnostics.typeIdentity(javaType.getRawClass())
                            + ": the profile's mapper deserializes it with "
                            + Diagnostics.truncate(
                                    deserializer.getClass().getName(), Diagnostics.MAX_SHORT_IDENTITY_LENGTH)
                            + ", whose wire shape the generator cannot describe; declare a JsonSchemaTypeOverride for"
                            + " the type on the profile, or deserialize it as a bean",
                    null);
        }
        ObjectNode definition = context.getGeneratorConfig().createObjectNode();
        ValueInstantiator instantiator = bean.getValueInstantiator();
        if (instantiator.canCreateUsingDelegate()) {
            // Treated exactly like a type-level custom deserializer (below): the whole object is bound
            // through a delegate type, so no named property is ever read from this type's own wire
            // shape, and a document describing the delegate's shape honestly would open the boundary to
            // keys main never accepted and leave any constraint on this type's own fields dead on input.
            throw Diagnostics.failure(
                    "JSON Schema generation failed for " + Diagnostics.typeIdentity(javaType.getRawClass())
                            + ": the type binds through a delegating @JsonCreator, which reads no named"
                            + " property of its own — its wire shape is whatever the delegate type's"
                            + " deserializer accepts, which a schema's properties cannot describe; declare a"
                            + " JsonSchemaTypeOverride for the type on the profile, or bind it through a"
                            + " property-based creator",
                    null);
        }
        String scalar = scalarCreator(instantiator);
        if (scalar != null && !instantiator.canCreateFromObjectWith() && !instantiator.canCreateUsingDefault()) {
            definition.put("type", scalar);
            return new CustomDefinition(
                    definition, CustomDefinition.DefinitionType.STANDARD, CustomDefinition.AttributeInclusion.NO);
        }

        boolean caseInsensitive = bean.isCaseInsensitive();
        populateObjectSchema(
                definition, javaType, resolved, bean, builderFor(javaType, bean), context, caseInsensitive);
        return new CustomDefinition(
                definition, CustomDefinition.DefinitionType.STANDARD, CustomDefinition.AttributeInclusion.YES);
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
     * @param context         the active generation context
     * @param caseInsensitive whether {@code bean} binds its properties case-insensitively
     */
    private void populateObjectSchema(
            ObjectNode definition,
            JavaType javaType,
            ResolvedType resolved,
            BeanDeserializerBase bean,
            BeanDeserializerBuilder builder,
            SchemaGenerationContext context,
            boolean caseInsensitive) {
        definition.put("type", "object");
        ObjectNode properties = definition.putObject("properties");
        List<String> required = new ArrayList<>();
        Class<?> builtClass = javaType.getRawClass();
        Set<String> published = new LinkedHashSet<>();
        Set<String> excludedFromReservation = new LinkedHashSet<>();
        List<SettableBeanProperty> bound = boundProperties(bean, builder);
        SettableAnyProperty anySetter = builder == null ? null : builder.getAnySetter();
        Set<Object> storage = storageMembers(javaType, anySetter);
        ObjectNode[] patternProperties = new ObjectNode[1];
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
            Iterator<SettableBeanProperty> it = builder.getProperties();
            while (it.hasNext()) {
                SettableBeanProperty property = it.next();
                NameTransformer transformer = introspector().findUnwrappingNameTransformer(property.getMember());
                if (transformer == null) {
                    continue;
                }
                JsonDeserializer<?> child = rootDeserializer(property.getType());
                JsonDeserializer<?> renamed = child == null ? null : child.unwrappingDeserializer(transformer);
                if (!(renamed instanceof BeanDeserializerBase unwrapped)) {
                    continue;
                }
                requireCaseSensitive(
                        unwrapped, property.getType().getRawClass(), "the unwrapped member " + property.getName());
                ResolvedType childResolved = resolve(context, property.getType());
                for (SettableBeanProperty childProperty : boundProperties(unwrapped, null)) {
                    String name = childProperty.getName();
                    if (name.isEmpty() || published.contains(name)) {
                        continue;
                    }
                    JsonNode schema = propertySchema(
                            childProperty, property.getType().getRawClass(), childResolved, context, required);
                    if (schema != null) {
                        properties.set(name, schema);
                        published.add(name);
                        if (caseInsensitive) {
                            publishFolded(definition, patternProperties, name, schema, builtClass);
                        }
                    }
                }
                if (anySetter == null) {
                    BeanDeserializerBuilder childBuilder = builderFor(property.getType(), unwrapped);
                    if (childBuilder != null) {
                        anySetter = childBuilder.getAnySetter();
                    }
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

        boolean extrasDescribed = describeExtras(definition, anySetter, builtClass, context);
        if (extrasDescribed) {
            Set<String> reserved = reservedNames(javaType, bean, bound, published, aliasPlan);
            reserved.removeAll(excludedFromReservation);
            if (!reserved.isEmpty()) {
                ObjectNode rule = JsonNodeFactory.instance.objectNode();
                if (caseInsensitive) {
                    rule.putObject("not").put("pattern", combinedFoldPattern(reserved, builtClass));
                } else {
                    ArrayNode values = rule.putObject("not").putArray("enum");
                    reserved.forEach(values::add);
                }
                definition.set("propertyNames", rule);
            }
        }
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

    /** A map subclass is bound as a map: its fields are never filled, and its entries are its values. */
    private CustomDefinition describeMapLike(JavaType javaType, SchemaGenerationContext context) {
        ObjectNode definition = context.getGeneratorConfig().createObjectNode();
        definition.put("type", "object");
        JavaType content = javaType.getContentType();
        if (content != null && !UNCONSTRAINED_VALUE_TYPES.contains(content.getRawClass())) {
            definition.set("additionalProperties", context.createDefinitionReference(resolve(context, content)));
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
        JsonDeserializer<?> valueDeserializer =
                property.hasValueDeserializer() ? unwrap(property.getValueDeserializer()) : null;
        if (valueDeserializer instanceof BeanDeserializerBase nestedBean && nestedBean.isCaseInsensitive()) {
            // Case-insensitive only through this member's own contextual
            // @JsonFormat(with = ACCEPT_CASE_INSENSITIVE_PROPERTIES) (or a mapper-wide feature reaching
            // it the same way): the type's ordinary, case-sensitive shared definition would misdescribe
            // it here, so it is described inline instead of by reference to that shared definition.
            JavaType memberType = property.getType();
            ObjectNode inline = context.getGeneratorConfig().createObjectNode();
            populateObjectSchema(
                    inline,
                    memberType,
                    resolve(context, memberType),
                    nestedBean,
                    builderFor(memberType, nestedBean),
                    context,
                    true);
            if (member != null) {
                translateConstraints(member, builtClass, inline, property.getName(), required);
            }
            return inline;
        }
        if (valueDeserializer instanceof StdDelegatingDeserializer<?> converting
                && converting.getDelegatee() != null
                && converting.getDelegatee().handledType() != null) {
            // A converter binds the wire value as its delegate's type, not as the member's declared type.
            JsonNode schema = context.createDefinitionReference(
                    context.getTypeContext().resolve(converting.getDelegatee().handledType()));
            if (member != null) {
                translateConstraints(member, builtClass, (ObjectNode) schema, property.getName(), required);
            }
            return schema;
        }
        if (raw instanceof Field field) {
            return fieldSchema(field, builtClass, resolved, property.getName(), context, required);
        }
        if (raw instanceof Method method) {
            return methodSchema(method, property, builtClass, resolved, context, required);
        }
        if (member instanceof AnnotatedParameter parameter) {
            Field backing = backingField(builtClass, parameter, property.getName());
            if (backing != null) {
                JsonNode schema = fieldSchema(backing, builtClass, resolved, property.getName(), context, required);
                if (schema != null && member != null) {
                    translateConstraints(member, builtClass, (ObjectNode) schema, property.getName(), required);
                }
                return schema;
            }
        }
        // A member the schema library has no scope for: the type from the deserializer, the
        // constraints from Jackson's merged annotation map.
        ObjectNode schema = context.createDefinitionReference(resolve(context, property.getType()));
        if (member != null) {
            translateConstraints(member, builtClass, schema, property.getName(), required);
        }
        boolean objectId = property instanceof com.fasterxml.jackson.databind.deser.impl.ObjectIdValueProperty;
        if (!objectId
                && property.getMetadata() != null
                && Boolean.TRUE.equals(property.getMetadata().getRequired())) {
            required.add(property.getName());
        }
        return schema;
    }

    private JsonNode fieldSchema(
            Field field,
            Class<?> builtClass,
            ResolvedType resolved,
            String name,
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
            applyScopedConstraints(schema, builtClass, field.getName(), name, required);
            return schema;
        }
        // A field the library's member resolution does not list (a static or synthetic one): by type.
        return context.createDefinitionReference(typeContext.resolve(field.getGenericType()));
    }

    /**
     * When the constraint source is metadata-driven, applies its constraints and required-ness to a
     * field or getter that a schema-library member scope already described — the generator disabled
     * its own Jakarta Validation module for exactly this reason. A no-op for the annotation walk,
     * which leaves a scoped member entirely to the library's own modules.
     *
     * @param schema     the schema the library produced for the member; may be a {@code $ref} wrapper,
     *                   in which case its {@code type} is unavailable and the value kind falls back to
     *                   {@link ConstraintValueKind#OTHER}
     * @param builtClass the type the property is described on
     * @param javaName   the field's or getter's Java bean name
     * @param wireName   the published property name, added to {@code required} when applicable
     * @param required   the object schema's required-property list
     */
    private void applyScopedConstraints(
            ObjectNode schema, Class<?> builtClass, String javaName, String wireName, List<String> required) {
        if (!constraintSource.disablesGeneratorJakartaModule()) {
            return;
        }
        ConstraintValueKind kind =
                ConstraintValueKind.fromSchemaType(schema.path("type").asText());
        ResolvedConstraints resolved = constraintSource.forScopedMember(builtClass, javaName, kind);
        applyResolvedConstraints(schema, resolved, wireName, required);
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
            translateConstraints(member, builtClass, schema, property.getName(), required);
            if (method.getDeclaringClass() != builtClass
                    && !method.getDeclaringClass().isAssignableFrom(builtClass)) {
                // a builder method: the constraints live on the built type's field of the same name
                borrowFieldAttributes(builtClass, method.getName(), property.getName(), schema, context, required);
            } else {
                // a setter: the field it implies by name — transient, private, or renamed on the wire —
                // still carries the constraints the developer wrote for the value
                borrowFieldAttributes(
                        method.getDeclaringClass(),
                        impliedFieldName(method),
                        property.getName(),
                        schema,
                        context,
                        required);
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
                applyScopedConstraints(object, builtClass, getterBeanName(method), property.getName(), required);
            }
            return schema;
        }
        ObjectNode schema = context.createDefinitionReference(resolve(context, property.getType()));
        translateConstraints(member, builtClass, schema, property.getName(), required);
        return schema;
    }

    /**
     * The built type's field of the given name, whose attributes a builder method borrows: the
     * constraints a developer writes on a builder type live on the built type's fields.
     */
    private static void borrowFieldAttributes(
            Class<?> builtClass,
            String memberName,
            String wireName,
            ObjectNode schema,
            SchemaGenerationContext context,
            List<String> required) {
        TypeContext typeContext = context.getTypeContext();
        for (Class<?> current = builtClass;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.isSynthetic()
                        || !(field.getName().equals(memberName)
                                || field.getName().equals(wireName))) {
                    continue;
                }
                ResolvedTypeWithMembers members = typeContext.resolveWithMembers(typeContext.resolve(current));
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
        String name = method.getName();
        for (String prefix : List.of("set", "with")) {
            if (name.length() > prefix.length() && name.startsWith(prefix)) {
                String rest = name.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return name;
    }

    /** The field behind a creator parameter: the record component's field, or the field named like the property. */
    private static Field backingField(Class<?> builtClass, AnnotatedParameter parameter, String wireName) {
        if (builtClass.isRecord()) {
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
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(wireName);
        if (parameter.getOwner() != null
                && parameter.getOwner().getMember() instanceof java.lang.reflect.Executable executable) {
            java.lang.reflect.Parameter[] parameters = executable.getParameters();
            int index = parameter.getIndex();
            if (index >= 0 && index < parameters.length && parameters[index].isNamePresent()) {
                candidates.add(parameters[index].getName()); // needs javac -parameters; absent otherwise
            }
        }
        for (Class<?> current = builtClass;
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && !field.isSynthetic()
                        && candidates.contains(field.getName())) {
                    return field;
                }
            }
        }
        return null;
    }

    /**
     * The constraints a member without a library scope needs — a creator parameter, a setter, or a
     * builder method — from {@link #constraintSource}: the annotation walk (Jackson's merged
     * annotation map) unless the generator was built with a {@link jakarta.validation.Validator}, in
     * which case Bean Validation metadata. The Swagger translation is source-independent and always
     * applied.
     */
    private void translateConstraints(
            AnnotatedMember member, Class<?> builtClass, ObjectNode schema, String name, List<String> required) {
        if (member == null) {
            return;
        }
        ConstraintValueKind kind =
                ConstraintValueKind.fromSchemaType(schema.path("type").asText());
        String javaName = javaBeanName(member);
        ResolvedConstraints resolved = constraintSource.forUnscopedMember(builtClass, javaName, kind, member);
        applyResolvedConstraints(schema, resolved, name, required);
        Schema swagger = member.getAnnotation(Schema.class);
        if (swagger != null) {
            translateSwagger(swagger, schema);
        }
    }

    /** Merges a resolved constraint set onto a schema and its required-list entry. */
    private static void applyResolvedConstraints(
            ObjectNode schema, ResolvedConstraints resolved, String wireName, List<String> required) {
        for (Map.Entry<String, Object> entry : resolved.keywords().entrySet()) {
            putKeyword(schema, entry.getKey(), entry.getValue());
        }
        if (resolved.required() && !required.contains(wireName)) {
            required.add(wireName);
        }
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
     * getter or setter implies by stripping its {@code get}/{@code is}/{@code set}/{@code with} prefix.
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
        String name = method.getName();
        for (String prefix : List.of("get", "is")) {
            if (name.length() > prefix.length() && name.startsWith(prefix)) {
                String rest = name.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return name;
    }

    /** The Swagger metadata a creator parameter or setter carries; a field or getter keeps the module's own handling. */
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
            schema.put("pattern", swagger.pattern());
        }
        if (swagger.minLength() != 0) {
            schema.put("minLength", swagger.minLength());
        }
        if (swagger.maxLength() != Integer.MAX_VALUE) {
            schema.put("maxLength", swagger.maxLength());
        }
        if (!swagger.minimum().isEmpty()) {
            schema.put(swagger.exclusiveMinimum() ? "exclusiveMinimum" : "minimum", new BigDecimal(swagger.minimum()));
        }
        if (!swagger.maximum().isEmpty()) {
            schema.put(swagger.exclusiveMaximum() ? "exclusiveMaximum" : "maximum", new BigDecimal(swagger.maximum()));
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
     * Publishes the any-setter's extras beside the named properties, unless the class declares
     * {@code @Schema(additionalProperties = FALSE)}, whose restriction the Swagger module then
     * publishes unopposed.
     *
     * @return whether extras are described
     */
    private boolean describeExtras(
            ObjectNode definition,
            SettableAnyProperty anySetter,
            Class<?> builtClass,
            SchemaGenerationContext context) {
        if (anySetter == null) {
            return false;
        }
        Schema schema = builtClass.getAnnotation(Schema.class);
        if (schema != null && schema.additionalProperties() == Schema.AdditionalPropertiesValue.FALSE) {
            return false;
        }
        JavaType valueType = anySetter.getType();
        if (valueType == null || UNCONSTRAINED_VALUE_TYPES.contains(valueType.getRawClass())) {
            definition.putObject("additionalProperties");
        } else {
            definition.set("additionalProperties", context.createDefinitionReference(resolve(context, valueType)));
        }
        // A bound on the any-setter's map itself — @Size on the field, @Schema(minProperties/maxProperties)
        // on the member — is a bound on the object's key count.
        AnnotatedMember member =
                anySetter.getProperty() == null ? null : anySetter.getProperty().getMember();
        if (member != null) {
            Size size = member.getAnnotation(Size.class);
            if (size != null && member.getRawType() != null && Map.class.isAssignableFrom(member.getRawType())) {
                if (size.max() != Integer.MAX_VALUE) {
                    definition.put("maxProperties", size.max());
                }
                if (size.min() > 0) {
                    definition.put("minProperties", size.min());
                }
            }
            Schema swagger = member.getAnnotation(Schema.class);
            if (swagger != null) {
                if (swagger.maxProperties() != 0) {
                    definition.put("maxProperties", swagger.maxProperties());
                }
                if (swagger.minProperties() != 0) {
                    definition.put("minProperties", swagger.minProperties());
                }
            }
        }
        return true;
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
     */
    private static void markNullable(ObjectNode schema) {
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
     * Whether a creator parameter's wire name has no member to publish it faithfully with: no field it
     * joins to — by the wire name, or, when the class was compiled with {@code javac -parameters}, by
     * the compiled parameter name — no getter- or setter-derived property of the same name either, and
     * no constraint annotation of its own.
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
        String name = method.getName();
        for (String prefix : List.of("get", "set", "with", "is")) {
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
     * {@code ^[nN][aA][mM][eE]$} — and every other character is escaped literally.
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
        return pattern.append('$').toString();
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
            alternatives.add(pattern.substring(1, pattern.length() - 1));
        }
        return "^(?:" + String.join("|", alternatives) + ")$";
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
     * The builder Jackson assembled the type's deserializer from, captured through a
     * {@code BeanDeserializerModifier} on a copy of the profile's mapper, since the resolved
     * deserializer exposes neither its any-setter nor its unwrapped members.
     */
    private BeanDeserializerBuilder builderFor(JavaType type, BeanDeserializerBase bean) {
        JavaType key = bean instanceof BuilderBasedDeserializer ? mapper.constructType(bean.getBeanClass()) : type;
        BeanDeserializerBuilder builder = builders.get(key);
        if (builder == null) {
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
            try {
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

    private static ResolvedType resolve(SchemaGenerationContext context, JavaType type) {
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
