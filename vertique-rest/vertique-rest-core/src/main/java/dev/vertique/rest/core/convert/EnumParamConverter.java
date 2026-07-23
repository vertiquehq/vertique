// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

/**
 * Generic, case-sensitive {@link ParamConverter} for an arbitrary enum type. {@code fromString}
 * delegates to {@link Enum#valueOf(Class, String)} (exact constant-name match); {@code toString}
 * returns {@link Enum#name()} so the transport form is always the declared constant name regardless
 * of any overridden {@code toString()} on the enum.
 *
 * <p>Instances are synthesized on demand by {@link ParamConverterRegistry} for any
 * {@code Class.isEnum()} target that has no exact-class binding.
 *
 * @param <E> the enum type this converter handles
 */
final class EnumParamConverter<E extends Enum<E>> implements ParamConverter<E> {

    private final Class<E> enumType;

    /**
     * Creates a converter for the given enum type.
     *
     * @param enumType the enum class; never {@code null}
     */
    EnumParamConverter(Class<E> enumType) {
        this.enumType = enumType;
    }

    @Override
    public E fromString(String value) {
        // Let Enum.valueOf throw its raw IllegalArgumentException on an unknown constant; the
        // ParamConversionResolver is the sole builder of ParamConversionException and re-contextualizes
        // this failure with the real parameter name/source/target type.
        return Enum.valueOf(enumType, value);
    }

    @Override
    public String toString(E value) {
        return value.name();
    }
}
