// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

/**
 * A profile-declared schema override for one exact raw Java class.
 *
 * <p>Skeleton: behavior is added in the green step of this slice.
 */
public final class JsonSchemaTypeOverride {

    /** The generation directions an override may apply to. */
    public enum Direction {
        /** Input direction. */
        INPUT,
        /** Output direction. */
        OUTPUT,
        /** Both directions. */
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
     * Creates an input-direction override.
     *
     * @param javaType the overridden class
     * @param fragment the fragment
     * @return the override
     */
    public static JsonSchemaTypeOverride input(Class<?> javaType, JsonSchemaFragment fragment) {
        return new JsonSchemaTypeOverride(javaType, Direction.INPUT, fragment);
    }

    /**
     * Creates an output-direction override.
     *
     * @param javaType the overridden class
     * @param fragment the fragment
     * @return the override
     */
    public static JsonSchemaTypeOverride output(Class<?> javaType, JsonSchemaFragment fragment) {
        return new JsonSchemaTypeOverride(javaType, Direction.INPUT, fragment);
    }

    /**
     * Creates a both-direction override.
     *
     * @param javaType the overridden class
     * @param fragment the fragment
     * @return the override
     */
    public static JsonSchemaTypeOverride both(Class<?> javaType, JsonSchemaFragment fragment) {
        return new JsonSchemaTypeOverride(javaType, Direction.INPUT, fragment);
    }

    /**
     * Returns the overridden class.
     *
     * @return the class
     */
    public Class<?> javaType() {
        return javaType;
    }

    /**
     * Returns the direction.
     *
     * @return the direction
     */
    public Direction direction() {
        return direction;
    }

    /**
     * Returns the fragment.
     *
     * @return the fragment
     */
    public JsonSchemaFragment fragment() {
        return fragment;
    }
}
