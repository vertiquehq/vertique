// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.core.json.JsonSchemaTypeOverride.Direction;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link JsonMapperProfile} read once, validated once, and reduced to exactly what generator
 * construction needs: the profile's mapper, and the canonical override fragments — parsed exactly
 * once, at construction — that apply in one direction, keyed by the exact raw Java class they
 * describe.
 *
 * <p>Reading the profile exactly once matters. The profile is caller-supplied, so a second call to
 * {@code mapper()} or {@code jsonSchemaTypeOverrides()} could legally return something else; the
 * generator would then be configured with values it never validated. Parsing each fragment exactly
 * once at construction — rather than on every {@link ProfileOverrideDefinitionProvider} call — means
 * a malformed fragment fails fast during generator construction instead of during the first
 * generation that happens to resolve it.
 *
 * <p><strong>Validation is whole-declaration.</strong> The full override list is expanded to
 * {@code (class, INPUT)} and {@code (class, OUTPUT)} keys — a {@code BOTH} declaration expanding to
 * both — and checked for duplicates <em>before</em> the requested direction is filtered. A profile
 * carrying any duplicate effective mapping is therefore rejected by both directions, regardless of
 * which direction the conflict actually affects: a malformed declaration is a declaration defect,
 * not a per-direction accident.
 *
 * <p>Every failure is a bounded {@link JsonSchemaGenerationException} naming only the profile id
 * when one is available, plus the offending Java class where that is the identity at fault.
 */
final class ValidatedProfile {

    /** The profile's mapper, captured once. */
    private final ObjectMapper mapper;

    /** Parsed fragment tree per exact raw class, for the selected direction only. */
    private final Map<Class<?>, JsonNode> fragmentsByType;

    private ValidatedProfile(ObjectMapper mapper, Map<Class<?>, JsonNode> fragmentsByType) {
        this.mapper = mapper;
        this.fragmentsByType = fragmentsByType;
    }

    /**
     * Validates a profile and selects the overrides applying in one direction.
     *
     * @param profile   the caller-supplied profile; must not be {@code null}
     * @param direction the direction being constructed, either {@link Direction#INPUT} or
     *                  {@link Direction#OUTPUT}
     * @return the validated, direction-filtered view of the profile
     * @throws JsonSchemaGenerationException if the profile's id, mapper, override list, an override,
     *     or an override member is {@code null}, if a fragment cannot be read back as JSON, if a
     *     selected fragment carries the alias-expansion keyword on a schema object, or if the whole
     *     declaration carries a duplicate effective {@code (class, direction)} mapping
     */
    static ValidatedProfile forDirection(JsonMapperProfile profile, Direction direction) {
        JsonProfileId id = profile.id();
        if (id == null || id.value() == null) {
            throw Diagnostics.failure("cannot construct a JSON Schema generator: the profile declares a null id", null);
        }
        String profileLabel =
                "profile '" + Diagnostics.truncate(id.value(), Diagnostics.MAX_SHORT_IDENTITY_LENGTH) + "'";

        ObjectMapper mapper = profile.mapper();
        if (mapper == null) {
            throw Diagnostics.failure(
                    "cannot construct a JSON Schema generator: " + profileLabel + " declares a null mapper", null);
        }

        List<JsonSchemaTypeOverride> declared = profile.jsonSchemaTypeOverrides();
        if (declared == null) {
            throw Diagnostics.failure(
                    "cannot construct a JSON Schema generator: " + profileLabel
                            + " declares a null schema type override list",
                    null);
        }

        Map<Class<?>, JsonNode> selected = new HashMap<>();
        Set<Map.Entry<Class<?>, Direction>> effectiveKeys = new HashSet<>();
        for (JsonSchemaTypeOverride override : declared) {
            validateAndCollect(override, direction, mapper, profileLabel, effectiveKeys, selected);
        }
        return new ValidatedProfile(mapper, Map.copyOf(selected));
    }

    /**
     * Validates one declared override, registers its expanded effective keys, and selects it — parsed
     * to a fresh {@link JsonNode} — when it applies in the requested direction.
     *
     * @param override      the declared override
     * @param direction     the direction being constructed
     * @param mapper        the profile's mapper, consulted only to decide whether an INPUT-direction
     *                      override's type is bean-like (F6)
     * @param profileLabel  the bounded profile label used in failure messages
     * @param effectiveKeys the accumulating set of expanded {@code (class, direction)} keys
     * @param selected      the accumulating direction-filtered fragment map
     * @throws JsonSchemaGenerationException if the override or one of its members is {@code null}, if
     *     the fragment cannot be read back as JSON, if a selected fragment carries the alias-expansion
     *     keyword at a schema position, if it duplicates an effective mapping already declared, or if
     *     an INPUT-direction override for a bean-like type declares neither {@code properties} nor
     *     {@code additionalProperties}
     */
    private static void validateAndCollect(
            JsonSchemaTypeOverride override,
            Direction direction,
            ObjectMapper mapper,
            String profileLabel,
            Set<Map.Entry<Class<?>, Direction>> effectiveKeys,
            Map<Class<?>, JsonNode> selected) {
        if (override == null) {
            throw Diagnostics.failure(
                    "cannot construct a JSON Schema generator: " + profileLabel
                            + " declares a null schema type override",
                    null);
        }
        Class<?> javaType = override.javaType();
        Direction declaredDirection = override.direction();
        JsonSchemaFragment fragment = override.fragment();
        if (javaType == null || declaredDirection == null || fragment == null || fragment.canonicalJson() == null) {
            throw Diagnostics.failure(
                    "cannot construct a JSON Schema generator: " + profileLabel
                            + " declares a schema type override with a null java type, direction, or fragment",
                    null);
        }

        for (Direction expanded : expand(declaredDirection)) {
            if (!effectiveKeys.add(Map.entry(javaType, expanded))) {
                throw Diagnostics.failure(
                        "cannot construct a JSON Schema generator: " + profileLabel
                                + " declares more than one effective " + expanded + " schema type override for "
                                + Diagnostics.typeIdentity(javaType),
                        null);
            }
        }

        if (declaredDirection == Direction.BOTH || declaredDirection == direction) {
            JsonNode parsed = parse(fragment.canonicalJson(), profileLabel, javaType);
            if (AnnotationJsonSchemaGenerator.AliasExpansion.fragmentCarriesMarker(parsed)) {
                throw Diagnostics.failure(
                        "cannot construct a JSON Schema generator: " + profileLabel
                                + " declares a schema type override fragment for " + Diagnostics.typeIdentity(javaType)
                                + " that carries the keyword \"" + AnnotationJsonSchemaGenerator.AliasExpansion.MARKER
                                + "\", which is reserved by the generator's alias expansion",
                        null);
            }
            if (direction == Direction.INPUT) {
                requireClosedOrDeclaredForBeanLikeType(mapper, javaType, parsed, profileLabel);
            }
            selected.put(javaType, parsed);
        }
    }

    /**
     * F6 (security review round 1, MEDIUM): the documented remedy for both new generator refusals
     * (F1/F3's, and the delegating-creator/case-insensitive ones from an earlier round) is "declare a
     * JsonSchemaTypeOverride". Nothing previously checked that a declared fragment for a refused
     * <em>bean</em> type actually describes or closes anything: {@code ValidatedProfile} checked only
     * nulls, duplicates, and the alias-expansion marker, so the natural remedy {@code {"type":"object"}}
     * was accepted at construction and yields, at MCP, a non-root object with no {@code properties} and
     * no {@code additionalProperties} — which {@code McpSchemaHardener} deliberately leaves open (it
     * only closes an object that already declares a non-empty {@code properties}). An INPUT override
     * for a bean-like class must now declare {@code properties} or an explicit {@code
     * additionalProperties} — or, per {@link #isFullyConstrainedShape} (W3, spike/deserializer-driven
     * -schema round 4 ruling), some other keyword that fully constrains the fragment's own wire shape
     * regardless: a {@code type} that is not (or does not contain) {@code "object"}, an {@code enum}, a
     * {@code const}, or a {@code oneOf}/{@code anyOf}/{@code allOf} whose every branch itself qualifies
     * — so the remedy cannot itself become an unconstrained, unclosed argument object at MCP or an
     * unconstrained body member at REST, without over-refusing a fragment that never had an
     * open-object position to begin with.
     *
     * <p>"Bean-like" is decided through {@link BeanLikeTypes#beanLike}, the one shared check (S5,
     * spike/deserializer-driven-schema round 4 ruling) {@link InputPropertyDescriber}'s own F1 refusal
     * also consults: whether the mapper's reflective introspection reports any settable property for the
     * class at all. A type with no introspected properties (a scalar, a container, a {@code Map}
     * subclass, a Vert.x-style wrapper) is not a bean the override could be leaving unconstrained, so it
     * is exempt. Output-direction overrides are unaffected: this check runs only for
     * {@code direction == INPUT}.
     *
     * @param mapper       the profile's mapper, whose reflective introspection decides bean-likeness
     * @param javaType     the overridden class
     * @param parsed       the override's parsed fragment tree
     * @param profileLabel the bounded profile label used in the failure message
     * @throws JsonSchemaGenerationException when {@code javaType} is bean-like and {@code parsed}
     *     declares neither {@code properties} nor {@code additionalProperties}
     */
    private static void requireClosedOrDeclaredForBeanLikeType(
            ObjectMapper mapper, Class<?> javaType, JsonNode parsed, String profileLabel) {
        if (isFullyConstrainedShape(parsed)) {
            return;
        }
        if (!BeanLikeTypes.beanLike(mapper, javaType)) {
            return;
        }
        throw Diagnostics.failure(
                "cannot construct a JSON Schema generator: " + profileLabel
                        + " declares an open schema type override for " + Diagnostics.typeIdentity(javaType)
                        + "; an input override for a bean-like type must declare properties or"
                        + " additionalProperties, or the type stays open at every position it resolves,"
                        + " unclosed by the MCP hardener",
                null);
    }

    /** The composition keywords {@link #isFullyConstrainedShape} descends into (W3, round 4 ruling). */
    private static final List<String> COMPOSITION_KEYWORDS = List.of("oneOf", "anyOf", "allOf");

    /**
     * W3 (spike/deserializer-driven-schema round 4 ruling): whether an override fragment fully
     * constrains its own wire shape, leaving no property-level position an unconstrained extra key
     * could hide in — the same open-object risk {@link #requireClosedOrDeclaredForBeanLikeType} exists
     * to catch. Exempted, beside declaring {@code properties} or {@code additionalProperties}: a
     * {@code type} that is a single non-{@code "object"} string, or an array not containing
     * {@code "object"}; an {@code enum}; a {@code const}; and a {@code oneOf}/{@code anyOf}/
     * {@code allOf} whose every branch itself satisfies this same rule, recursively. A bare
     * {@code {"type":"object"}} — the pre-existing refused control shape — satisfies none of these and
     * stays refused for a bean-like type.
     *
     * @param fragment the override fragment (or, recursively, one of its composition branches)
     * @return {@code true} when {@code fragment} carries no open-object risk on its own
     */
    private static boolean isFullyConstrainedShape(JsonNode fragment) {
        if (fragment.has("properties") || fragment.has("additionalProperties")) {
            return true;
        }
        if (fragment.has("enum") || fragment.has("const")) {
            return true;
        }
        JsonNode declaredType = fragment.get("type");
        if (declaredType != null) {
            if (declaredType.isTextual() && !"object".equals(declaredType.textValue())) {
                // The fragment replaces the type wholesale with a non-object shape (a string, a number,
                // an array, ...): it is fully constrained by that declared type, with no property-level
                // position for an unconstrained extra key to hide in.
                return true;
            }
            if (declaredType.isArray()) {
                boolean containsObject = false;
                for (JsonNode entry : declaredType) {
                    if (entry.isTextual() && "object".equals(entry.textValue())) {
                        containsObject = true;
                        break;
                    }
                }
                if (!containsObject) {
                    return true;
                }
            }
        }
        for (String composition : COMPOSITION_KEYWORDS) {
            JsonNode branches = fragment.get(composition);
            if (branches == null || !branches.isArray() || branches.isEmpty()) {
                continue;
            }
            boolean everyBranchConstrained = true;
            for (JsonNode branch : branches) {
                if (!branch.isObject() || !isFullyConstrainedShape(branch)) {
                    everyBranchConstrained = false;
                    break;
                }
            }
            if (everyBranchConstrained) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses one declared override's canonical fragment text into a tree, once.
     *
     * @param canonicalJson the fragment's canonical JSON text
     * @param profileLabel  the bounded profile label used in the failure message
     * @param javaType      the class the fragment was declared for, named in the failure message
     * @return the parsed, unshared fragment tree
     * @throws JsonSchemaGenerationException if the text cannot be read back as JSON — unreachable in
     *     practice, since {@link JsonSchemaFragment} only ever holds text it parsed itself
     */
    private static JsonNode parse(String canonicalJson, String profileLabel, Class<?> javaType) {
        try {
            return NeutralJson.read(canonicalJson);
        } catch (JsonProcessingException malformed) {
            throw Diagnostics.failure(
                    "cannot construct a JSON Schema generator: " + profileLabel
                            + " declares a schema type override fragment for " + Diagnostics.typeIdentity(javaType)
                            + " that is not readable JSON",
                    malformed);
        }
    }

    /**
     * Expands a declared direction into the effective directions it occupies.
     *
     * @param declared the declared direction
     * @return {@code [INPUT, OUTPUT]} for {@link Direction#BOTH}, otherwise the declared direction alone
     */
    private static List<Direction> expand(Direction declared) {
        return declared == Direction.BOTH ? List.of(Direction.INPUT, Direction.OUTPUT) : List.of(declared);
    }

    /**
     * Returns the profile's mapper, captured at validation time.
     *
     * @return the mapper Victools property discovery is driven by
     */
    ObjectMapper mapper() {
        return mapper;
    }

    /**
     * Returns whether any override applies in the selected direction.
     *
     * @return {@code true} when at least one override was selected
     */
    boolean hasOverrides() {
        return !fragmentsByType.isEmpty();
    }

    /**
     * Looks up the parsed fragment tree declared for an exact raw class.
     *
     * <p>The match is by exact class: no assignability, so a subclass of an overridden class is not
     * covered by its supertype's declaration. The returned tree is the single instance parsed at
     * construction — shared across every call — so a caller that embeds it in a mutable document must
     * copy it first.
     *
     * @param rawType the erased class of a resolved type
     * @return the parsed fragment tree, or {@code null} when the class carries no override
     */
    JsonNode fragmentFor(Class<?> rawType) {
        return rawType == null ? null : fragmentsByType.get(rawType);
    }
}
