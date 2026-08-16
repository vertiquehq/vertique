// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

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
 * construction needs: the profile's mapper, and the canonical override fragments that apply in one
 * direction, keyed by the exact raw Java class they describe.
 *
 * <p>Reading the profile exactly once matters. The profile is caller-supplied, so a second call to
 * {@code mapper()} or {@code jsonSchemaTypeOverrides()} could legally return something else; the
 * generator would then be configured with values it never validated.
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

    /** Canonical fragment JSON per exact raw class, for the selected direction only. */
    private final Map<Class<?>, String> fragmentsByType;

    private ValidatedProfile(ObjectMapper mapper, Map<Class<?>, String> fragmentsByType) {
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
     *     or an override member is {@code null}, or if the whole declaration carries a duplicate
     *     effective {@code (class, direction)} mapping
     */
    static ValidatedProfile forDirection(JsonMapperProfile profile, Direction direction) {
        JsonProfileId id = profile.id();
        if (id == null || id.value() == null) {
            throw Diagnostics.failure("cannot construct a JSON Schema generator: the profile declares a null id", null);
        }
        String profileLabel = "profile '" + Diagnostics.truncate(id.value(), 128) + "'";

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

        Map<Class<?>, String> selected = new HashMap<>();
        Set<String> effectiveKeys = new HashSet<>();
        for (JsonSchemaTypeOverride override : declared) {
            validateAndCollect(override, direction, profileLabel, effectiveKeys, selected);
        }
        return new ValidatedProfile(mapper, Map.copyOf(selected));
    }

    /**
     * Validates one declared override, registers its expanded effective keys, and selects it when it
     * applies in the requested direction.
     *
     * @param override      the declared override
     * @param direction     the direction being constructed
     * @param profileLabel  the bounded profile label used in failure messages
     * @param effectiveKeys the accumulating set of expanded {@code (class, direction)} keys
     * @param selected      the accumulating direction-filtered fragment map
     * @throws JsonSchemaGenerationException if the override or one of its members is {@code null}, or
     *     if it duplicates an effective mapping already declared
     */
    private static void validateAndCollect(
            JsonSchemaTypeOverride override,
            Direction direction,
            String profileLabel,
            Set<String> effectiveKeys,
            Map<Class<?>, String> selected) {
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
            if (!effectiveKeys.add(javaType.getName() + "@" + expanded)) {
                throw Diagnostics.failure(
                        "cannot construct a JSON Schema generator: " + profileLabel
                                + " declares more than one effective " + expanded + " schema type override for "
                                + Diagnostics.typeIdentity(javaType),
                        null);
            }
        }

        if (declaredDirection == Direction.BOTH || declaredDirection == direction) {
            selected.put(javaType, fragment.canonicalJson());
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
     * Looks up the canonical fragment declared for an exact raw class.
     *
     * <p>The match is by exact class: no assignability, so a subclass of an overridden class is not
     * covered by its supertype's declaration.
     *
     * @param rawType the erased class of a resolved type
     * @return the canonical fragment JSON, or {@code null} when the class carries no override
     */
    String fragmentFor(Class<?> rawType) {
        return rawType == null ? null : fragmentsByType.get(rawType);
    }
}
