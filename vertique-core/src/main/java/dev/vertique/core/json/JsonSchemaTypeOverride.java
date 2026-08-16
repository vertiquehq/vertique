// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import java.util.Objects;

/**
 * One profile-declared JSON Schema override binding an exact raw Java class to a
 * {@link JsonSchemaFragment}.
 *
 * <p>A {@link JsonMapperProfile} declares overrides so that a schema generated for the profile
 * describes the wire form the profile's mapper actually produces or accepts — for example a
 * {@code BigDecimal} serialized as a constrained string.
 *
 * <p>Matching is by <strong>exact raw class</strong>: no assignability, no subtype matching, and no
 * application to map keys. An override declared for {@code BigDecimal} applies to a
 * {@code BigDecimal} property and to the element type of a resolved {@code List<BigDecimal>}, but
 * not to a subclass of {@code BigDecimal} and not to the key type of a
 * {@code Map<BigDecimal, String>}.
 *
 * <p>{@link Direction} selects the construction modes the override participates in. Instances are
 * immutable.
 */
public final class JsonSchemaTypeOverride {

    /** The schema construction directions an override may apply to. */
    public enum Direction {

        /** Applies only when a schema is generated for the input (deserialization) direction. */
        INPUT,

        /** Applies only when a schema is generated for the output (serialization) direction. */
        OUTPUT,

        /** Applies to both the input and the output direction. */
        BOTH
    }

    private final Class<?> javaType;
    private final Direction direction;
    private final JsonSchemaFragment fragment;

    private JsonSchemaTypeOverride(Class<?> javaType, Direction direction, JsonSchemaFragment fragment) {
        this.javaType = javaType;
        this.direction = direction;
        this.fragment = fragment;
    }

    /**
     * Creates an override that applies only to the input direction.
     *
     * @param javaType the exact raw class the fragment describes
     * @param fragment the schema fragment to apply
     * @return the immutable override
     * @throws NullPointerException if {@code javaType} or {@code fragment} is {@code null}
     */
    public static JsonSchemaTypeOverride input(Class<?> javaType, JsonSchemaFragment fragment) {
        return create(javaType, Direction.INPUT, fragment);
    }

    /**
     * Creates an override that applies only to the output direction.
     *
     * @param javaType the exact raw class the fragment describes
     * @param fragment the schema fragment to apply
     * @return the immutable override
     * @throws NullPointerException if {@code javaType} or {@code fragment} is {@code null}
     */
    public static JsonSchemaTypeOverride output(Class<?> javaType, JsonSchemaFragment fragment) {
        return create(javaType, Direction.OUTPUT, fragment);
    }

    /**
     * Creates an override that applies to both directions.
     *
     * @param javaType the exact raw class the fragment describes
     * @param fragment the schema fragment to apply
     * @return the immutable override
     * @throws NullPointerException if {@code javaType} or {@code fragment} is {@code null}
     */
    public static JsonSchemaTypeOverride both(Class<?> javaType, JsonSchemaFragment fragment) {
        return create(javaType, Direction.BOTH, fragment);
    }

    /**
     * Returns the exact raw Java class this override describes.
     *
     * @return the non-null overridden class
     */
    public Class<?> javaType() {
        return javaType;
    }

    /**
     * Returns the direction in which this override applies.
     *
     * @return the non-null direction
     */
    public Direction direction() {
        return direction;
    }

    /**
     * Returns the schema fragment this override applies.
     *
     * @return the non-null fragment
     */
    public JsonSchemaFragment fragment() {
        return fragment;
    }

    private static JsonSchemaTypeOverride create(Class<?> javaType, Direction direction, JsonSchemaFragment fragment) {
        Objects.requireNonNull(javaType, "javaType");
        Objects.requireNonNull(fragment, "fragment");
        return new JsonSchemaTypeOverride(javaType, direction, fragment);
    }
}
