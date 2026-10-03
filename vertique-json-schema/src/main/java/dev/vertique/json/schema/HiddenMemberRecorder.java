// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.ResolvedTypeWithMembers;
import com.fasterxml.classmate.members.ResolvedMember;
import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedClassResolver;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.AnnotatedWithParams;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.AnnotationHelper;
import com.github.victools.jsonschema.generator.MemberScope;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import com.github.victools.jsonschema.generator.TypeContext;
import com.github.victools.jsonschema.generator.TypeScope;
import dev.vertique.core.json.JsonSchemaTypeOverride.Direction;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Records, while a recording is open, every member and every type a canonical generator describes
 * that carries {@link Hidden}, {@code @Schema(hidden = true)}, or both: the entries {@link
 * AnnotationJsonSchemaGenerator#hiddenMembers(java.lang.reflect.Type)} reports.
 *
 * <p>A generation reaches it through entry points none of which modifies a node, so it changes no
 * document: a type attribute hook the schema library calls for every type it describes, in both
 * directions; a member attribute hook in each direction, which the schema library calls for every
 * member it describes — in the output direction for each member its walk publishes, and in the input
 * direction for each member it describes itself, such as those of a polymorphic base or an abstract type
 * the input describer leaves to it, and each member scope the describer builds a property from; a second
 * type attribute hook, in both directions, for the {@code @JsonUnwrapped} members of each type it
 * describes; and the input direction's describer, which records each property it describes, an
 * unwrapped member included, each any-setter whose extras it describes, and each type it describes
 * without the schema library traversing it. Every entry point does nothing unless a recording is open.
 * Describedness comes from these entry points alone: a member a generator leaves out, because it
 * honors {@code @Schema(hidden = true)} there or because the profile mapper does not bind (input) or
 * serialize (output) it, reaches none of them.
 *
 * <p>One predicate reads both markers on a declaration: {@code HIDDEN} when only {@code @Hidden} is
 * present, {@code SCHEMA_HIDDEN} when only {@code @Schema(hidden = true)} is, {@code BOTH} when both
 * are. Neither marker excludes the other. A type, a constant, a field, and a creator parameter are
 * read from the annotations the profile mapper merges into them, in the recorder's direction — its
 * deserialization view for the input direction, its serialization view for the output direction —
 * exactly as the mapper reads them, directly or through a Jackson annotation bundle ({@link
 * JacksonAnnotationsInside}) at any depth: a type's view holds its own annotations and those of the
 * mix-in the mapper registers for it and that mix-in's superclasses, never those of the type's own
 * supertypes, except that a type whose view holds no {@code @Schema} takes the one it inherits from a
 * superclass, since {@code @Schema} is {@code @Inherited}, while {@code @Hidden}, which is not, counts
 * only where the view holds it; a field's view, within the type it is described in, adds those of the
 * mix-in the mapper registers for the field's own class; a creator parameter's view adds those of the
 * mix-in's matching creator. A method declaration is read by itself, never through the mapper's merged
 * view of its signature: its own annotations and those of the matching method of the mix-in the mapper
 * registers for its own class and that mix-in's superclasses, each directly or through a bundle at any
 * depth, a mix-in's {@code @Schema} taking precedence over the declaration's own. A member the
 * introspection holds no view of is read the same way. The mapper's merge across a property's
 * accessors, which copies one accessor's annotations onto another, is never read. A mix-in's marker is reported at its
 * target; a mix-in is never recorded, since the generation never describes one.
 *
 * <p>A described type is recorded when it carries a marker; an enum type it describes also has each of
 * its constants carrying one recorded, under the constant's name. Each member hook, in either
 * direction, also walks the member's declared type through the positions the generator describes as
 * the member's value — an array's element type, the payload of an {@code Optional} or a {@code
 * Supplier}, which the schema library flattens, an {@code Iterable}'s element type, and, in the input
 * direction only, a map's value type, at any depth — and records each type the walk reaches that the
 * member is described as, or as a subtype of: the schema library's Jackson module describes a
 * polymorphic member, or a polymorphic container element, through its subtypes, so the declared base
 * never reaches the type hook. The member's own scope walks its value down to the first array or
 * collection the schema library describes as a container, and that container's item view walks on from
 * there, so a redirect declared for the items alone is honored just as one declared for the member. A type
 * the walk reaches only through a redirect to an unrelated type is not recorded. The walk does not
 * consult the profile's overrides: the Jackson module describes a polymorphic base through its subtypes
 * even when the profile overrides the base, so a type the walk reaches is recorded whether or not an
 * override replaces its definition.
 *
 * <p>An unwrapped member is described only through its flattened content: the schema library's Jackson
 * module drops the member itself from the walk before any member hook runs, and describes its type's
 * content inside the declaring type's own definition instead. Both directions therefore record it from
 * the declaring type's hook, selecting the members exactly as that module selects the ones it flattens,
 * and reading each one's carriers from the profile mapper's introspection like a described member's; a
 * member the introspection drops, such as a read-only member in the input direction, is recorded as its
 * own and only carrier. A type the profile overrides is skipped, since the override replaces its
 * definition and nothing is flattened into it.
 *
 * <p>A property's carriers are its field, its getter, its setter, and its creator parameter, as the
 * profile mapper's introspection of the type the member is described in links them, together with the
 * member the generation describes it through. The type the member is described in is a subclass of the
 * member's declaring type when the member is inherited, so a getter or setter the subclass overrides is
 * the carrier the mapper links. In the input direction, a field the schema library describes that the
 * deserialization introspection links to no property — a private field the mapper drops from a
 * property it binds through a setter — belongs to the property of the field's own name, whose setter
 * is a carrier. A field is recorded under its own name and declaring type; a creator parameter under
 * its creator's name, {@code <init>} for a constructor, then {@code #} and its zero-based index, and
 * the creator's declaring type. A method carrier is read together with each method of its name whose
 * parameter types resolve, within the type the member is described in, to its own — the declarations
 * it overrides or implements, a generic one included; for each of them whose merged view carries a
 * marker, each declaration of that signature in the type's hierarchy that carries a marker by itself
 * is recorded under its own name and declaring type, and when none does, since a mix-in registered for
 * another class of the hierarchy put the marker there, the method the mapper binds the signature to is
 * recorded with the marker of its merged view.
 *
 * <p>Each recording carries whether declaring {@code @Schema(hidden = true)} directly on the property's
 * own field, or on its getter when the mapper sees no field, makes the generator leave the property
 * out at the position recorded: the caller computes it from the path it describes the property through.
 * The output direction's member hook records {@code true}; a type, a constant, an unwrapped member, and
 * an any-setter are recorded {@code false}; the input direction's member hook records {@code true},
 * except while the describer borrows a field's attributes for a property it describes through a setter
 * or a builder method (see {@link #setBorrowing}), when it records {@code false}; and the describer
 * passes its own path's value for each property it describes. An entry is recorded once per declaring
 * type and member however often a generation reaches it, carrying the conjunction of the values
 * recorded for it, so the advice holds at every position.
 *
 * <p>Recording state is read and written only under the owning generator's instance lock.
 */
final class HiddenMemberRecorder {

    /** The report's order: declaring type, then member, the type-level entry first. */
    private static final Comparator<HiddenMember> REPORT_ORDER = Comparator.comparing(HiddenMember::declaringType)
            .thenComparing(HiddenMember::member, Comparator.nullsFirst(Comparator.naturalOrder()));

    /** Matches an annotation bundle, which Jackson and the schema library's Jackson module both read through. */
    private static final Predicate<Annotation> JACKSON_BUNDLE =
            annotation -> annotation.annotationType().isAnnotationPresent(JacksonAnnotationsInside.class);

    private final ObjectMapper mapper;

    /** Whether the recorder serves the input direction, whose view of the mapper is its deserialization view. */
    private final boolean input;

    /** The profile mapper's configuration in the recorder's direction. */
    private final MapperConfig<?> config;

    /** The profile's direction-filtered overrides, consulted to skip a type an override replaces. */
    private final ValidatedProfile profile;

    /**
     * The entries of the generation being recorded, keyed by declaring type and member, or {@code null}
     * when no recording is open.
     */
    private Map<Key, HiddenMember> recording;

    /**
     * Whether the input describer is borrowing a field's attributes for a property it describes through a
     * setter or a builder method, so the input member hook records {@code false}.
     */
    private boolean borrowing;

    /** Each type's introspection in the recorder's direction. */
    private final ClassValue<Introspection> introspections = new ClassValue<>() {
        @Override
        protected Introspection computeValue(Class<?> type) {
            JavaType javaType = mapper.getTypeFactory().constructType(type);
            return Introspection.of(
                    input
                            ? mapper.getDeserializationConfig().introspect(javaType)
                            : mapper.getSerializationConfig().introspect(javaType));
        }
    };

    /**
     * Each type's own annotated view in the recorder's direction, with its creators: resolved apart from
     * the introspection, whose property merge replaces a creator parameter's annotations in place with
     * those merged from the property's other accessors.
     */
    private final ClassValue<AnnotatedClass> creatorViews = new ClassValue<>() {
        @Override
        protected AnnotatedClass computeValue(Class<?> type) {
            return AnnotatedClassResolver.resolve(
                    config, mapper.getTypeFactory().constructType(type), config);
        }
    };

    /** An entry's identity: the report holds one entry per declaring type and member. */
    private record Key(String declaringType, String member) {}

    /**
     * A type's introspection in the recorder's direction.
     *
     * @param properties its properties, keyed by every field, getter, and setter each links
     * @param members    its fields and member methods, each with the annotations the mapper merges into it
     *                   from the type's hierarchy and mix-ins, before any merge across a property's
     *                   accessors
     * @param byMember   {@code members}' fields and methods, keyed by the Java member each views
     */
    private record Introspection(
            Map<Member, BeanPropertyDefinition> properties,
            AnnotatedClass members,
            Map<Member, AnnotatedMember> byMember) {

        static Introspection of(BeanDescription description) {
            Map<Member, BeanPropertyDefinition> properties = new HashMap<>();
            for (BeanPropertyDefinition property : description.findProperties()) {
                for (AnnotatedMember member :
                        new AnnotatedMember[] {property.getField(), property.getGetter(), property.getSetter()}) {
                    if (member != null) {
                        properties.putIfAbsent(member.getMember(), property);
                    }
                }
            }
            AnnotatedClass members = description.getClassInfo();
            Map<Member, AnnotatedMember> byMember = new HashMap<>();
            for (AnnotatedField field : members.fields()) {
                byMember.put(field.getAnnotated(), field);
            }
            for (AnnotatedMethod method : members.memberMethods()) {
                byMember.put(method.getAnnotated(), method);
            }
            return new Introspection(Map.copyOf(properties), members, Map.copyOf(byMember));
        }
    }

    /**
     * @param profile   the validated, direction-filtered profile: its mapper's introspection links a
     *                  member the generation describes to its property's other carriers and merges the
     *                  annotations each carries, and its overrides name the types whose definition an
     *                  override replaces
     * @param direction the direction the owning generator describes, {@link Direction#INPUT} or {@link
     *                  Direction#OUTPUT}, which selects the mapper's deserialization or serialization view
     */
    HiddenMemberRecorder(ValidatedProfile profile, Direction direction) {
        this.mapper = profile.mapper();
        this.profile = profile;
        this.input = direction == Direction.INPUT;
        this.config = input ? mapper.getDeserializationConfig() : mapper.getSerializationConfig();
    }

    /** Opens a recording for the generation about to run, discarding any earlier one. */
    void beginRecording() {
        recording = new HashMap<>();
    }

    /**
     * Whether a recording is open.
     *
     * @return {@code true} while a recording is open
     */
    boolean isRecording() {
        return recording != null;
    }

    /**
     * The entries the open recording holds, in report order.
     *
     * @return an unmodifiable list, empty when nothing was recorded
     */
    List<HiddenMember> recordedMembers() {
        return recording.values().stream().sorted(REPORT_ORDER).toList();
    }

    /** Closes the recording, whether the generation it recorded succeeded or failed. */
    void endRecording() {
        recording = null;
    }

    /**
     * Sets whether the input describer is borrowing a field's attributes for a property it describes
     * through a setter or a builder method: while it is, the input member hook records {@code false},
     * since that field's {@code @Schema(hidden = true)} does not leave such a property out.
     *
     * @param borrowing whether a borrow is in progress
     * @return the previous value, which the caller restores when its borrow ends
     */
    boolean setBorrowing(boolean borrowing) {
        boolean previous = this.borrowing;
        this.borrowing = borrowing;
        return previous;
    }

    /**
     * The type attribute hook: the schema library calls it for each type it describes, after every
     * other type attribute has been collected. It never modifies {@code attributes}.
     *
     * @param attributes the type's collected attributes, left untouched
     * @param scope      the described type
     * @param context    the generation context, unused
     */
    void recordDescribedType(ObjectNode attributes, TypeScope scope, SchemaGenerationContext context) {
        if (recording == null) {
            return;
        }
        recordType(scope.getType().getErasedType());
    }

    /**
     * Records a described type when it carries a marker, and, for an enum type, each of its constants that
     * does. Neither is hideable by {@code @Schema(hidden = true)} on a field or getter.
     *
     * @param type the described type
     */
    void recordType(Class<?> type) {
        if (recording == null) {
            return;
        }
        AnnotatedClass view = AnnotatedClassResolver.resolveWithoutSuperTypes(config, type);
        HidingMarker typeMarker = typeMarkerOf(view, type);
        if (typeMarker != null) {
            record(type.getName(), null, typeMarker, false);
        }
        if (type.isEnum()) {
            JavaType enumType = mapper.getTypeFactory().constructType(type);
            for (AnnotatedField constant :
                    AnnotatedClassResolver.resolve(config, enumType, config).fields()) {
                HidingMarker constantMarker = constant.getAnnotated().isEnumConstant() ? markerOf(constant) : null;
                if (constantMarker != null) {
                    record(type.getName(), constant.getName(), constantMarker, false);
                }
            }
        }
    }

    /**
     * The output direction's member attribute hook: the schema library calls it for each member it
     * publishes, after its ignore checks have run, and for a container's item view of it. It never
     * modifies {@code attributes}. Either scope records the types its declared type reaches, map values
     * excepted, since the output direction describes none (see {@link #recordReachedTypes}); the
     * member's own scope also records its property's carriers, read from the profile mapper's
     * serialization introspection of the type it is described in, as hideable: the output direction
     * honors {@code @Schema(hidden = true)} on the field or getter of every member it publishes.
     *
     * @param attributes the member's collected attributes, left untouched
     * @param scope      the published member, or a container's item view of it
     * @param context    the generation context, whose type context tells a container apart
     */
    void recordPublishedMember(ObjectNode attributes, MemberScope<?, ?> scope, SchemaGenerationContext context) {
        if (recording == null) {
            return;
        }
        recordReachedTypes(scope, false, context);
        if (!scope.isFakeContainerItemScope()) {
            recordIntrospectedMember(describedIn(scope), scope.getRawMember(), true);
        }
    }

    /**
     * The input direction's member attribute hook: the schema library calls it for each member it
     * describes, after its ignore checks have run, for a container's item view of it, and for each member
     * scope the input describer builds a property's schema from or borrows a field's attributes through.
     * It never modifies {@code attributes}. Either scope records the types its declared type reaches, map
     * values included (see {@link #recordReachedTypes}); the member's own scope also records its
     * property's carriers, read from the profile mapper's deserialization introspection of the type it is
     * described in (see {@link #boundProperty}), as hideable unless a borrow is in progress (see {@link
     * #setBorrowing}).
     *
     * @param attributes the member's collected attributes, left untouched
     * @param scope      the described member, or a container's item view of it
     * @param context    the generation context, whose type context tells a container apart
     */
    void recordBoundMember(ObjectNode attributes, MemberScope<?, ?> scope, SchemaGenerationContext context) {
        if (recording == null) {
            return;
        }
        recordReachedTypes(scope, true, context);
        if (!scope.isFakeContainerItemScope()) {
            Class<?> type = describedIn(scope);
            Member member = scope.getRawMember();
            recordProperty(type, member, boundProperty(type, member), !borrowing);
        }
    }

    /**
     * The deserialized property of {@code type} that a member the input direction describes belongs to:
     * the one linking it as its field, getter, or setter; or, for a field the introspection links to no
     * property, the property of the field's own name that links no field. The profile mapper groups a
     * field with its setter under the field's name and then drops a field it does not see from the
     * property, so a private field the schema library describes still belongs to the property its setter
     * binds. {@code null} when neither exists.
     */
    private BeanPropertyDefinition boundProperty(Class<?> type, Member member) {
        Map<Member, BeanPropertyDefinition> properties =
                introspections.get(type).properties();
        BeanPropertyDefinition linked = properties.get(member);
        if (linked != null || !(member instanceof Field field)) {
            return linked;
        }
        for (BeanPropertyDefinition candidate : properties.values()) {
            if (candidate.getField() == null && candidate.getInternalName().equals(field.getName())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The type a member scope is described in: the type whose definition the scope was created for,
     * which is a subclass of the member's declaring type when the member is inherited, or the declaring
     * type when the scope names no other.
     */
    private static Class<?> describedIn(MemberScope<?, ?> scope) {
        MemberScope.DeclarationDetails details = scope.getDeclarationDetails();
        ResolvedType target = details == null ? null : details.getSchemaTargetType();
        return (target == null ? scope.getDeclaringType() : target).getErasedType();
    }

    /**
     * Records each type a described member's declared type reaches that the member is described as, or
     * as a subtype of. The declared type and the type the scope describes — after the schema library's
     * own flattening, subtype resolution, and target-type redirects, which for a container's item view
     * is the item type — are each walked by {@link #collectReachedTypes}; a type the declared walk
     * reaches is recorded when some type the described walk reaches is it or a subtype of it. A declared
     * type replaced by an unrelated one through a redirect is therefore not recorded. The member's own
     * scope stops both walks at a container, whose elements the schema library describes through the
     * container's item view, where a redirect declared for the items alone applies; the item view walks
     * the member's whole declared type against the item type it describes.
     *
     * @param scope     the described member, or a container's item view of it
     * @param mapValues whether the direction describes a map's values, which only the input direction
     *                  does
     * @param context   the generation context, whose type context tells a container apart
     */
    private void recordReachedTypes(MemberScope<?, ?> scope, boolean mapValues, SchemaGenerationContext context) {
        ResolvedType declared = scope.getDeclaredType();
        ResolvedType described = scope.getType();
        if (declared == null || described == null) {
            return;
        }
        TypeContext containers = scope.isFakeContainerItemScope() ? null : context.getTypeContext();
        Set<Class<?>> describedTypes = new HashSet<>();
        collectReachedTypes(described, mapValues, containers, describedTypes, new HashSet<>());
        Set<Class<?>> declaredTypes = new LinkedHashSet<>();
        collectReachedTypes(declared, mapValues, containers, declaredTypes, new HashSet<>());
        for (Class<?> type : declaredTypes) {
            if (describedTypes.stream().anyMatch(type::isAssignableFrom)) {
                recordType(type);
            }
        }
    }

    /**
     * Collects {@code type} and each type below it at a position the generator describes as part of a
     * member's value: an array's element type; the payload of an {@link Optional} or a {@link Supplier},
     * the wrappers the schema library flattens; an {@link Iterable}'s element type; and, when {@code
     * mapValues} holds, a {@link Map}'s value type, never its key type. Each binding is read through
     * the type's supertypes, so a subclass binding its element or payload in its {@code extends} or
     * {@code implements} clause is walked too, and a type the profile overrides is walked like any other.
     * A type already visited is not walked again, which bounds a self-referential binding.
     *
     * @param containers the type context whose containers' elements are left to their item view, or
     *                   {@code null} to walk through containers
     */
    private void collectReachedTypes(
            ResolvedType type,
            boolean mapValues,
            TypeContext containers,
            Set<Class<?>> reached,
            Set<ResolvedType> visited) {
        if (type == null || !visited.add(type)) {
            return;
        }
        reached.add(type.getErasedType());
        if (containers != null && containers.isContainerType(type)) {
            return;
        }
        List<ResolvedType> content = new ArrayList<>();
        if (type.isArray()) {
            content.add(type.getArrayElementType());
        }
        addBinding(content, type, Optional.class, 0);
        addBinding(content, type, Supplier.class, 0);
        addBinding(content, type, Iterable.class, 0);
        if (mapValues) {
            addBinding(content, type, Map.class, 1);
        }
        for (ResolvedType child : content) {
            collectReachedTypes(child, mapValues, containers, reached, visited);
        }
    }

    /** Adds {@code type}'s binding of {@code supertype}'s type parameter at {@code index}, when it has one. */
    private static void addBinding(List<ResolvedType> content, ResolvedType type, Class<?> supertype, int index) {
        List<ResolvedType> bindings = type.typeParametersFor(supertype);
        if (bindings != null && bindings.size() > index) {
            content.add(bindings.get(index));
        }
    }

    /**
     * The unwrapped-member hook, in both directions: the schema library calls it, like {@link
     * #recordDescribedType}, for each type it describes. It never modifies {@code attributes}. Each
     * field and method of the type carrying an enabled {@code @JsonUnwrapped}, directly or through a
     * Jackson annotation bundle, is recorded as described through the content the schema library's
     * Jackson module flattens into the type, unless the profile overrides the type. The members are
     * selected from the type's own members, so one the profile mapper's introspection drops in the
     * recorder's direction is still recorded, as its own and only carrier. An unwrapped member is never
     * hideable by {@code @Schema(hidden = true)}, which neither generator honors on it.
     *
     * @param attributes the type's collected attributes, left untouched
     * @param scope      the described type
     * @param context    the generation context, whose type context resolves the type's members
     */
    void recordFlattenedMembers(ObjectNode attributes, TypeScope scope, SchemaGenerationContext context) {
        if (recording == null || profile.fragmentFor(scope.getType().getErasedType()) != null) {
            return;
        }
        ResolvedTypeWithMembers members = context.getTypeContext().resolveWithMembers(scope.getType());
        List<ResolvedMember<?>> candidates = new ArrayList<>(List.of(members.getMemberFields()));
        candidates.addAll(List.of(members.getMemberMethods()));
        for (ResolvedMember<?> candidate : candidates) {
            if (AnnotationHelper.resolveAnnotation(candidate, JsonUnwrapped.class, JACKSON_BUNDLE)
                    .filter(JsonUnwrapped::enabled)
                    .isPresent()) {
                recordIntrospectedMember(scope.getType().getErasedType(), candidate.getRawMember(), false);
            }
        }
    }

    /**
     * Records a described member, with its property's carriers read from the profile mapper's
     * introspection of {@code type} in the recorder's direction.
     *
     * @param type     the type the member is described in
     * @param member   the field or method the generation describes the property through
     * @param hideable whether {@code @Schema(hidden = true)} on the property's field or getter leaves it
     *                 out at this position
     */
    private void recordIntrospectedMember(Class<?> type, Member member, boolean hideable) {
        recordProperty(type, member, introspections.get(type).properties().get(member), hideable);
    }

    /**
     * Records a described property's carriers that carry a marker: its field, getter, setter, and creator
     * parameter, and the member it is described through, each method carrier together with the
     * declarations it overrides or implements.
     *
     * @param type      the type the property is described in; a carrier declared by a type that is not
     *                  one of its supertypes, such as a builder's method, is read within its own
     *                  declaring type
     * @param described the field or method the generation describes the property through, or {@code
     *                  null} when it is described through neither (a creator parameter)
     * @param property  the property as the profile mapper's introspection links it, or {@code null} when
     *                  the introspection does not know it
     * @param hideable  whether {@code @Schema(hidden = true)} declared directly on the property's field, or
     *                  on its getter when the mapper sees no field, leaves the property out at the
     *                  position the caller describes it at
     */
    void recordProperty(Class<?> type, Member described, BeanPropertyDefinition property, boolean hideable) {
        if (recording == null) {
            return;
        }
        Member field = property == null ? null : memberOf(property.getField());
        Member getter = property == null ? null : memberOf(property.getGetter());
        Member setter = property == null ? null : memberOf(property.getSetter());
        for (Member carrier : new Member[] {described, field, getter, setter}) {
            if (carrier instanceof Field fieldCarrier) {
                recordField(type, fieldCarrier, hideable);
            } else if (carrier instanceof Method methodCarrier) {
                recordMethod(type, methodCarrier, hideable);
            }
        }
        AnnotatedParameter parameter = property == null ? null : property.getConstructorParameter();
        if (parameter != null) {
            recordParameter(parameter, hideable);
        }
    }

    /**
     * Records an any-setter declared on a creator parameter, whose extras the input direction's describer
     * has described, when the parameter carries a marker. The parameter is its own and only carrier: the
     * profile mapper's introspection links it to no property. It is recorded {@code false}, since {@code
     * @Schema(hidden = true)} never leaves an any-setter's extras out.
     *
     * @param parameter the creator parameter the any-setter binds
     */
    void recordAnySetterParameter(AnnotatedParameter parameter) {
        if (recording == null) {
            return;
        }
        recordParameter(parameter, false);
    }

    /**
     * Records a field carrier when its merged view within {@code type}, or, when the mapper's
     * introspection holds no view of it, the field itself, carries a marker.
     */
    private void recordField(Class<?> type, Field field, boolean hideable) {
        Annotated view = view(type, field);
        HidingMarker marker = view != null ? markerOf(view) : declarationMarker(field);
        if (marker != null) {
            record(field, marker, hideable);
        }
    }

    /**
     * Records a creator parameter carrier when it carries a marker, under its creator's name — {@code
     * <init>} for a constructor — followed by {@code #} and its zero-based index, and the creator's
     * declaring type. The parameter is read through its own view of the creator, never the property's,
     * which carries the annotations the mapper merges onto it from the property's other accessors; when
     * no view holds the creator, the parameter's own declarations are read.
     */
    private void recordParameter(AnnotatedParameter parameter, boolean hideable) {
        Member creator = parameter.getOwner().getMember();
        int index = parameter.getIndex();
        Annotated view = creatorParameterView(creator, index);
        HidingMarker marker = view != null ? markerOf(view) : declaredParameterMarker(creator, index);
        if (marker == null) {
            return;
        }
        String creatorName = creator instanceof Constructor<?> ? "<init>" : creator.getName();
        record(creator.getDeclaringClass().getName(), creatorName + "#" + index, marker, hideable);
    }

    /**
     * The view of parameter {@code index} of {@code creator} within the creator's declaring type (see
     * {@link #creatorViews}), with its own annotations and its mix-in's, or {@code null} when that view
     * holds no such creator.
     */
    private Annotated creatorParameterView(Member creator, int index) {
        AnnotatedClass members = creatorViews.get(creator.getDeclaringClass());
        List<? extends AnnotatedWithParams> creators =
                creator instanceof Constructor<?> ? members.getConstructors() : members.getFactoryMethods();
        for (AnnotatedWithParams candidate : creators) {
            if (candidate.getMember().equals(creator) && index < candidate.getParameterCount()) {
                return candidate.getParameter(index);
            }
        }
        return null;
    }

    /** The marker parameter {@code index} of {@code creator} declares by itself, directly or through a bundle. */
    private static HidingMarker declaredParameterMarker(Member creator, int index) {
        Parameter[] parameters = ((Executable) creator).getParameters();
        if (index >= parameters.length) {
            return null;
        }
        Annotation[] own = parameters[index].getDeclaredAnnotations();
        Schema schema = declared(own, Schema.class, new HashSet<>());
        return markerOf(declared(own, Hidden.class, new HashSet<>()) != null, schema != null && schema.hidden());
    }

    /**
     * Records the declarations that give a method carrier a marker. Each member method of the type the
     * carrier is read within whose name and resolved parameter types are the carrier's own — the carrier
     * itself, and a generic declaration it overrides with concrete parameter types — is read for a marker
     * through its merged view; for each one carrying one, each declaration of that method's signature in
     * the type's hierarchy that carries a marker by itself (see {@link #declarationMarker}) is recorded
     * with that marker, and when none does, since a mix-in registered for another class of the hierarchy
     * put it there, the method the mapper binds the signature to is recorded with the marker of its merged
     * view.
     */
    private void recordMethod(Class<?> type, Method method, boolean hideable) {
        Class<?> owner = owner(type, method);
        Introspection introspection = introspections.get(owner);
        if (!(introspection.byMember().get(method) instanceof AnnotatedMethod carrier)) {
            HidingMarker marker = declarationMarker(method);
            if (marker != null) {
                record(method, marker, hideable);
            }
            return;
        }
        for (AnnotatedMethod related : introspection.members().memberMethods()) {
            HidingMarker merged = markerOf(related);
            if (merged == null || !sameResolvedSignature(related, carrier)) {
                continue;
            }
            boolean declared = false;
            for (Method declaration : declarations(owner, related)) {
                HidingMarker marker = declarationMarker(declaration);
                if (marker != null) {
                    declared = true;
                    record(declaration, marker, hideable);
                }
            }
            if (!declared) {
                record(related.getAnnotated(), merged, hideable);
            }
        }
    }

    /** Whether two member methods share their name and their parameter types, each resolved within the type. */
    private static boolean sameResolvedSignature(AnnotatedMethod candidate, AnnotatedMethod carrier) {
        if (!candidate.getName().equals(carrier.getName())
                || candidate.getParameterCount() != carrier.getParameterCount()) {
            return false;
        }
        for (int index = 0; index < carrier.getParameterCount(); index++) {
            if (candidate.getParameterType(index).getRawClass()
                    != carrier.getParameterType(index).getRawClass()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Each method declared by {@code owner} or one of its supertypes, {@link Object} excluded, with the
     * name and the raw parameter types of {@code signature}: the declarations the mapper merges into
     * that signature. Static, synthetic, and bridge methods are never declarations.
     */
    private static List<Method> declarations(Class<?> owner, AnnotatedMethod signature) {
        List<Method> declarations = new ArrayList<>();
        Set<Class<?>> visited = new HashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(List.of(owner));
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (type == Object.class || !visited.add(type)) {
                continue;
            }
            for (Method candidate : type.getDeclaredMethods()) {
                if (candidate.getName().equals(signature.getName())
                        && !Modifier.isStatic(candidate.getModifiers())
                        && !candidate.isSynthetic()
                        && !candidate.isBridge()
                        && Arrays.equals(candidate.getParameterTypes(), signature.getRawParameterTypes())) {
                    declarations.add(candidate);
                }
            }
            addSupertypes(type, pending);
        }
        return declarations;
    }

    /**
     * The marker a field or method declaration carries by itself: its own annotations, and those of the
     * same-named field, or the method of the same name and parameter types, that the mix-in the mapper
     * registers for the declaration's own class, or one of that mix-in's supertypes, declares — each read
     * directly or through a Jackson annotation bundle at any depth. The first mix-in {@code @Schema} found
     * takes precedence over the declaration's own, as the mapper merges them. {@code null} when neither
     * marker is present.
     */
    private HidingMarker declarationMarker(Member declaration) {
        Annotation[] own = ((AnnotatedElement) declaration).getDeclaredAnnotations();
        boolean hidden = declared(own, Hidden.class, new HashSet<>()) != null;
        Schema schema = null;
        Class<?> mixIn = config.findMixInClassFor(declaration.getDeclaringClass());
        Deque<Class<?>> pending = new ArrayDeque<>();
        if (mixIn != null) {
            pending.add(mixIn);
        }
        Set<Class<?>> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (type == Object.class || !visited.add(type)) {
                continue;
            }
            for (AnnotatedElement counterpart : counterparts(type, declaration)) {
                Annotation[] annotations = counterpart.getDeclaredAnnotations();
                hidden |= declared(annotations, Hidden.class, new HashSet<>()) != null;
                if (schema == null) {
                    schema = declared(annotations, Schema.class, new HashSet<>());
                }
            }
            addSupertypes(type, pending);
        }
        if (schema == null) {
            schema = declared(own, Schema.class, new HashSet<>());
        }
        return markerOf(hidden, schema != null && schema.hidden());
    }

    /**
     * The members {@code type} declares that match {@code declaration}: the field of its name, or each
     * method of its name and parameter types.
     */
    private static List<AnnotatedElement> counterparts(Class<?> type, Member declaration) {
        List<AnnotatedElement> counterparts = new ArrayList<>();
        if (declaration instanceof Field) {
            for (Field candidate : type.getDeclaredFields()) {
                if (candidate.getName().equals(declaration.getName())) {
                    counterparts.add(candidate);
                }
            }
        } else if (declaration instanceof Method method) {
            for (Method candidate : type.getDeclaredMethods()) {
                if (candidate.getName().equals(method.getName())
                        && Arrays.equals(candidate.getParameterTypes(), method.getParameterTypes())) {
                    counterparts.add(candidate);
                }
            }
        }
        return counterparts;
    }

    /**
     * The mapper's merged view of {@code member} within the type it is read within (see {@link #owner}),
     * or {@code null} when the mapper's introspection holds no view of it.
     */
    private Annotated view(Class<?> type, Member member) {
        return introspections.get(owner(type, member)).byMember().get(member);
    }

    /**
     * The type {@code member} is read within: {@code type} when the member is declared by it or one of
     * its supertypes, and otherwise the member's own declaring type.
     */
    private static Class<?> owner(Class<?> type, Member member) {
        return member.getDeclaringClass().isAssignableFrom(type) ? type : member.getDeclaringClass();
    }

    /** The marker a merged view carries, or {@code null} when it carries neither. */
    private static HidingMarker markerOf(Annotated view) {
        Schema schema = view.getAnnotation(Schema.class);
        return markerOf(view.hasAnnotation(Hidden.class), schema != null && schema.hidden());
    }

    /**
     * The marker a type carries: that of its own view, whose {@code @Schema} is a mix-in's before the
     * type's own, except that when the view holds no {@code @Schema} the one {@code type} inherits from a
     * superclass decides, as {@link Class#getAnnotation} reads it. {@code @Hidden} is not inherited, so
     * only the view's counts. {@code null} when the type carries neither marker.
     */
    private static HidingMarker typeMarkerOf(AnnotatedClass view, Class<?> type) {
        Schema schema = view.getAnnotation(Schema.class);
        if (schema == null) {
            schema = type.getAnnotation(Schema.class);
        }
        return markerOf(view.hasAnnotation(Hidden.class), schema != null && schema.hidden());
    }

    /** The marker for the markers present, or {@code null} when neither is. */
    private static HidingMarker markerOf(boolean hidden, boolean schemaHidden) {
        if (hidden) {
            return schemaHidden ? HidingMarker.BOTH : HidingMarker.HIDDEN;
        }
        return schemaHidden ? HidingMarker.SCHEMA_HIDDEN : null;
    }

    /**
     * The annotation of {@code type} among {@code annotations}, declared directly or, when none is,
     * through a Jackson annotation bundle at any depth, the first bundle in declaration order that holds
     * one deciding, as the profile mapper's introspection expands a bundle; {@code null} when absent.
     */
    private static <A extends Annotation> A declared(
            Annotation[] annotations, Class<A> type, Set<Class<?>> visitedBundles) {
        for (Annotation annotation : annotations) {
            if (type.isInstance(annotation)) {
                return type.cast(annotation);
            }
        }
        for (Annotation annotation : annotations) {
            if (JACKSON_BUNDLE.test(annotation) && visitedBundles.add(annotation.annotationType())) {
                A bundled = declared(annotation.annotationType().getDeclaredAnnotations(), type, visitedBundles);
                if (bundled != null) {
                    return bundled;
                }
            }
        }
        return null;
    }

    private void record(Member member, HidingMarker marker, boolean hideable) {
        record(member.getDeclaringClass().getName(), member.getName(), marker, hideable);
    }

    /**
     * Records one entry; an entry already recorded keeps one place in the report, its hideable value
     * becomes the conjunction of both, and differing markers combine into {@code BOTH}.
     */
    private void record(String declaringType, String member, HidingMarker marker, boolean hideable) {
        recording.merge(
                new Key(declaringType, member),
                new HiddenMember(declaringType, member, marker, hideable),
                (recorded, added) -> new HiddenMember(
                        declaringType,
                        member,
                        recorded.marker() == added.marker() ? recorded.marker() : HidingMarker.BOTH,
                        recorded.hideableBySchemaHidden() && added.hideableBySchemaHidden()));
    }

    private static void addSupertypes(Class<?> type, Deque<Class<?>> pending) {
        if (type.getSuperclass() != null) {
            pending.addLast(type.getSuperclass());
        }
        pending.addAll(List.of(type.getInterfaces()));
    }

    private static Member memberOf(AnnotatedMember member) {
        return member == null ? null : member.getMember();
    }
}
