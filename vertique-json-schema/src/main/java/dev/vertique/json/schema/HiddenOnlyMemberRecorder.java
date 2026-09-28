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
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * that carries {@link Hidden} without {@code @Schema(hidden = true)}: the entries {@link
 * AnnotationJsonSchemaGenerator#hiddenOnlyMembers(java.lang.reflect.Type)} reports.
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
 *
 * <p>Whether a type, a constant, or a member carries {@code @Hidden} or {@code @Schema} is read from the
 * annotations the profile mapper merges into it, in the recorder's direction — its deserialization view
 * for the input direction, its serialization view for the output direction — exactly as the mapper
 * reads them, directly or through a Jackson annotation bundle ({@link JacksonAnnotationsInside}) at any
 * depth: a type's view holds its own annotations and those of the mix-in the mapper registers for it and
 * that mix-in's superclasses, never those of the type's own supertypes; a field's or a method's view,
 * within the type it is described in, adds those of each same-signature declaration the type inherits
 * and of the mix-ins the mapper registers across the type's hierarchy. A mix-in's {@code @Schema} takes
 * precedence over its target's own, as the mapper merges them. The mapper's merge across a property's
 * accessors, which copies one accessor's annotations onto another, is never read.
 *
 * <p>A described type is recorded when it carries {@code @Hidden}; an enum type it describes also has
 * each of its constants carrying {@code @Hidden} recorded, under the constant's name. Each member hook,
 * in either direction, also walks the member's declared type through the positions the generator
 * describes as the member's value — an array's element type, the payload of an {@code Optional} or a
 * {@code Supplier}, which the schema library flattens, an {@code Iterable}'s element type, and, in the
 * input direction only, a map's value type, at any depth — and records each type the walk reaches that
 * the member is described as, or as a subtype of: the schema library's Jackson module describes a
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
 * <p>A property's carriers are its field, its getter, and its setter, as the profile mapper's
 * introspection of the type the member is described in links them, together with the member the
 * generation describes it through. The type the member is described in is a subclass of the member's
 * declaring type when the member is inherited, so a getter or setter the subclass overrides is the
 * carrier the mapper links. In the input direction, a field the schema library describes that the
 * deserialization introspection links to no property — a private field the mapper drops from a
 * property it binds through a setter — belongs to the property of the field's own name, whose setter
 * is a carrier. A field carrying {@code @Hidden} is recorded under its own name and declaring type. A
 * method carrier is read together with each method of its name whose parameter types resolve, within
 * the type the member is described in, to its own — the declarations it overrides or implements, a
 * generic one included; for each of them carrying {@code @Hidden}, each declaration of that signature
 * in the type's hierarchy that declares {@code @Hidden} itself, or through the mix-in the mapper
 * registers for its own class, is recorded under its own name and declaring type, and when none does,
 * since a mix-in registered for another class of the hierarchy put it there, the method the mapper
 * binds that signature to is recorded only when its merged view is hidden-only. A property whose
 * {@code @Schema} — read on the member the generation describes it through when that is its getter,
 * otherwise on its field and then its getter, and on the described member alone when the
 * introspection links neither and it is a field or a parameterless method — a setter-only property
 * reads none, since neither generator honors {@code @Schema(hidden = true)} on a setter — the first
 * annotation found deciding — says {@code hidden = true} is not recorded, since the generator hides
 * it. Each declaration is judged by its own markers: a field carrier is left out when its merged view
 * carries {@code @Schema(hidden = true)}, and a method declaration carrying {@code @Hidden} is left
 * out only when its own {@code @Schema}, or the one its own class's mix-in declares for it, says
 * hidden. A method declaration's own {@code @Schema} is read from its direct annotations and from the
 * mix-in the mapper registers for its own declaring class, which takes precedence, without expanding
 * an annotation bundle. A type or constant is recorded unless it also carries {@code @Schema(hidden =
 * true)}. An entry is recorded once however often a generation reaches it; a mix-in is never
 * recorded, since the generation never describes one.
 *
 * <p>Recording state is read and written only under the owning generator's instance lock.
 */
final class HiddenOnlyMemberRecorder {

    /** The report's order: declaring type, then member, the type-level entry first. */
    private static final Comparator<HiddenOnlyMember> REPORT_ORDER = Comparator.comparing(
                    HiddenOnlyMember::declaringType)
            .thenComparing(HiddenOnlyMember::member, Comparator.nullsFirst(Comparator.naturalOrder()));

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
     * The entries of the generation being recorded, or {@code null} when no recording is open.
     */
    private Set<HiddenOnlyMember> recording;

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
    HiddenOnlyMemberRecorder(ValidatedProfile profile, Direction direction) {
        this.mapper = profile.mapper();
        this.profile = profile;
        this.input = direction == Direction.INPUT;
        this.config = input ? mapper.getDeserializationConfig() : mapper.getSerializationConfig();
    }

    /** Opens a recording for the generation about to run, discarding any earlier one. */
    void beginRecording() {
        recording = new HashSet<>();
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
    List<HiddenOnlyMember> recordedMembers() {
        return recording.stream().sorted(REPORT_ORDER).toList();
    }

    /** Closes the recording, whether the generation it recorded succeeded or failed. */
    void endRecording() {
        recording = null;
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
     * Records a described type when it carries {@code @Hidden} and not {@code @Schema(hidden = true)},
     * and, for an enum type, each of its constants that does.
     *
     * @param type the described type
     */
    void recordType(Class<?> type) {
        if (recording == null) {
            return;
        }
        AnnotatedClass view = AnnotatedClassResolver.resolveWithoutSuperTypes(config, type);
        if (hiddenOnly(view)) {
            recording.add(new HiddenOnlyMember(type.getName(), null));
        }
        if (type.isEnum()) {
            JavaType enumType = mapper.getTypeFactory().constructType(type);
            for (AnnotatedField constant :
                    AnnotatedClassResolver.resolve(config, enumType, config).fields()) {
                if (constant.getAnnotated().isEnumConstant() && hiddenOnly(constant)) {
                    recording.add(new HiddenOnlyMember(type.getName(), constant.getName()));
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
     * serialization introspection of the type it is described in.
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
            recordIntrospectedMember(describedIn(scope), scope.getRawMember());
        }
    }

    /**
     * The input direction's member attribute hook: the schema library calls it for each member it
     * describes, after its ignore checks have run, for a container's item view of it, and for each member
     * scope the input describer builds a property's schema from. It never modifies {@code attributes}.
     * Either scope records the types its declared type reaches, map values included (see {@link
     * #recordReachedTypes}); the member's own scope also records its property's carriers, read from the
     * profile mapper's deserialization introspection of the type it is described in (see {@link
     * #boundProperty}).
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
            recordProperty(type, member, boundProperty(type, member));
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
     * recorder's direction is still recorded, as its own and only carrier.
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
                recordIntrospectedMember(scope.getType().getErasedType(), candidate.getRawMember());
            }
        }
    }

    /**
     * Records a described member, with its property's carriers read from the profile mapper's
     * introspection of {@code type} in the recorder's direction.
     *
     * @param type   the type the member is described in
     * @param member the field or method the generation describes the property through
     */
    private void recordIntrospectedMember(Class<?> type, Member member) {
        recordProperty(type, member, introspections.get(type).properties().get(member));
    }

    /**
     * Records a described property's carriers that carry {@code @Hidden}, each method carrier together
     * with the declarations it overrides or implements, unless its {@code @Schema} reading hides it.
     *
     * @param type      the type the property is described in; a carrier declared by a type that is not
     *                  one of its supertypes, such as a builder's method, is read within its own
     *                  declaring type
     * @param described the field or method the generation describes the property through, or {@code
     *                  null} when it is described through neither (a creator parameter)
     * @param property  the property as the profile mapper's introspection links it, or {@code null} when
     *                  the introspection does not know it
     */
    void recordProperty(Class<?> type, Member described, BeanPropertyDefinition property) {
        if (recording == null) {
            return;
        }
        Member field = property == null ? null : memberOf(property.getField());
        Member getter = property == null ? null : memberOf(property.getGetter());
        Member setter = property == null ? null : memberOf(property.getSetter());
        if (hiddenBySchema(type, described, field, getter)) {
            return;
        }
        for (Member carrier : new Member[] {described, field, getter, setter}) {
            if (carrier instanceof Field fieldCarrier) {
                recordField(type, fieldCarrier);
            } else if (carrier instanceof Method methodCarrier) {
                recordMethod(type, methodCarrier);
            }
        }
    }

    /**
     * Records a field carrier when its merged view within {@code type}, or, when the mapper's
     * introspection holds no view of it, the field itself, carries {@code @Hidden} without also
     * carrying {@code @Schema(hidden = true)}.
     */
    private void recordField(Class<?> type, Field field) {
        Annotated view = view(type, field);
        if (view != null ? hiddenOnly(view) : declaresHiddenOnly(field)) {
            record(field);
        }
    }

    /**
     * Records the declarations that give a method carrier {@code @Hidden}. Each member method of the type
     * the carrier is read within whose name and resolved parameter types are the carrier's own — the
     * carrier itself, and a generic declaration it overrides with concrete parameter types — is read for
     * {@code @Hidden} through its merged view; for each one carrying it, each declaration of that method's
     * signature in the type's hierarchy that declares {@code @Hidden} itself, or through the mix-in the
     * mapper registers for its own class, is recorded unless its own {@code @Schema}, or the one its own
     * class's mix-in declares for it, says {@code hidden = true}, and when none does, since a mix-in
     * registered for another class of the hierarchy put it there, the method the mapper binds the
     * signature to is recorded only when its merged view carries {@code @Hidden} without also carrying
     * {@code @Schema(hidden = true)}.
     */
    private void recordMethod(Class<?> type, Method method) {
        Class<?> owner = owner(type, method);
        Introspection introspection = introspections.get(owner);
        if (!(introspection.byMember().get(method) instanceof AnnotatedMethod carrier)) {
            if (declaresHiddenOnly(method)) {
                record(method);
            }
            return;
        }
        for (AnnotatedMethod related : introspection.members().memberMethods()) {
            if (!related.hasAnnotation(Hidden.class) || !sameResolvedSignature(related, carrier)) {
                continue;
            }
            boolean declared = false;
            for (Method declaration : declarations(owner, related)) {
                if (declaresHidden(declaration) || mixInDeclaresHidden(declaration)) {
                    declared = true;
                    if (!declaresSchemaHidden(declaration)) {
                        record(declaration);
                    }
                }
            }
            if (!declared && hiddenOnly(related)) {
                record(related.getAnnotated());
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
     * Whether the mix-in the mapper registers for {@code declaration}'s own class, or one of that
     * mix-in's supertypes, declares a method of its name and parameter types carrying {@code @Hidden}:
     * the mapper merges it into the declaration, the mix-in's target.
     */
    private boolean mixInDeclaresHidden(Method declaration) {
        Class<?> mixIn = config.findMixInClassFor(declaration.getDeclaringClass());
        if (mixIn == null) {
            return false;
        }
        Set<Class<?>> visited = new HashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(List.of(mixIn));
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (type == Object.class || !visited.add(type)) {
                continue;
            }
            for (Method candidate : type.getDeclaredMethods()) {
                if (candidate.getName().equals(declaration.getName())
                        && Arrays.equals(candidate.getParameterTypes(), declaration.getParameterTypes())
                        && declaresHidden(candidate)) {
                    return true;
                }
            }
            addSupertypes(type, pending);
        }
        return false;
    }

    /**
     * Whether the property's {@code @Schema} says {@code hidden = true}: read on its getter and then its
     * field when the generation describes it through the getter, otherwise on its field and then its
     * getter, and on the described member alone when the introspection links neither and it is a field or
     * a parameterless method — a setter-only property reads none, since neither generator honors
     * {@code @Schema(hidden = true)} on a setter — the first annotation found deciding. Each member's
     * {@code @Schema} is read from its merged view within {@code type}.
     */
    private boolean hiddenBySchema(Class<?> type, Member described, Member field, Member getter) {
        Member[] reading;
        if (field == null && getter == null) {
            reading = described instanceof Method method && method.getParameterCount() > 0
                    ? new Member[0]
                    : new Member[] {described};
        } else if (getter != null && getter.equals(described)) {
            reading = new Member[] {getter, field};
        } else {
            reading = new Member[] {field, getter};
        }
        for (Member member : reading) {
            Schema schema = schemaOf(type, member);
            if (schema != null) {
                return schema.hidden();
            }
        }
        return false;
    }

    /** The {@code @Schema} {@code member}'s merged view within {@code type} carries, or {@code null}. */
    private Schema schemaOf(Class<?> type, Member member) {
        if (member == null) {
            return null;
        }
        Annotated view = view(type, member);
        if (view != null) {
            return view.getAnnotation(Schema.class);
        }
        return member instanceof AnnotatedElement element ? element.getDeclaredAnnotation(Schema.class) : null;
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

    /** Whether a merged view carries {@code @Hidden} and not {@code @Schema(hidden = true)}. */
    private static boolean hiddenOnly(Annotated view) {
        Schema schema = view.getAnnotation(Schema.class);
        return view.hasAnnotation(Hidden.class) && (schema == null || !schema.hidden());
    }

    private void record(Member member) {
        recording.add(new HiddenOnlyMember(member.getDeclaringClass().getName(), member.getName()));
    }

    /**
     * Whether {@code element} declares {@code @Hidden}, directly or through a Jackson annotation bundle
     * at any depth, as the profile mapper's introspection expands a bundle. Only the element's own
     * declarations are read.
     */
    private static boolean declaresHidden(AnnotatedElement element) {
        return declaresHidden(element.getDeclaredAnnotations(), new HashSet<>());
    }

    private static boolean declaresHidden(Annotation[] annotations, Set<Class<?>> visitedBundles) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Hidden) {
                return true;
            }
            if (JACKSON_BUNDLE.test(annotation)
                    && visitedBundles.add(annotation.annotationType())
                    && declaresHidden(annotation.annotationType().getDeclaredAnnotations(), visitedBundles)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code element} declares {@code @Hidden} without also declaring {@code @Schema(hidden =
     * true)}, each read from {@code element}'s own declarations only, for a carrier the mapper's
     * introspection holds no merged view of.
     */
    private static boolean declaresHiddenOnly(AnnotatedElement element) {
        Schema schema = element.getDeclaredAnnotation(Schema.class);
        return declaresHidden(element) && (schema == null || !schema.hidden());
    }

    /** Whether {@code declaration}'s own {@code @Schema}, its own class's mix-in's taking precedence, says hidden. */
    private boolean declaresSchemaHidden(Method declaration) {
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
            for (Method candidate : type.getDeclaredMethods()) {
                if (candidate.getName().equals(declaration.getName())
                        && Arrays.equals(candidate.getParameterTypes(), declaration.getParameterTypes())
                        && candidate.getDeclaredAnnotation(Schema.class) != null) {
                    return candidate.getDeclaredAnnotation(Schema.class).hidden();
                }
            }
            addSupertypes(type, pending);
        }
        Schema own = declaration.getDeclaredAnnotation(Schema.class);
        return own != null && own.hidden();
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
