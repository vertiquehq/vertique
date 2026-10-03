// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.metadata;

import jakarta.annotation.Nullable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A parameterized type whose type arguments were substituted after a type variable was resolved.
 *
 * <p>Equality and hash code follow the JDK's own parameterized type, so an instance equals the JDK's
 * representation of the same type in both directions and the two are interchangeable as map keys.
 */
final class ResolvedParameterizedType implements ParameterizedType {

    private final Class<?> rawType;
    private final Type[] actualTypeArguments;
    private final @Nullable Type ownerType;

    /**
     * Creates the parameterized type.
     *
     * @param rawType the raw class
     * @param actualTypeArguments the type arguments, in declaration order
     * @param ownerType the owner type, or {@code null} for a top-level class
     */
    ResolvedParameterizedType(Class<?> rawType, Type[] actualTypeArguments, @Nullable Type ownerType) {
        this.rawType = Objects.requireNonNull(rawType, "rawType");
        this.actualTypeArguments = actualTypeArguments.clone();
        this.ownerType = ownerType;
    }

    @Override
    public Type[] getActualTypeArguments() {
        return actualTypeArguments.clone();
    }

    @Override
    public Type getRawType() {
        return rawType;
    }

    @Override
    public @Nullable Type getOwnerType() {
        return ownerType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ParameterizedType that)) {
            return false;
        }
        return Objects.equals(ownerType, that.getOwnerType())
                && Objects.equals(rawType, that.getRawType())
                && Arrays.equals(actualTypeArguments, that.getActualTypeArguments());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(actualTypeArguments) ^ Objects.hashCode(ownerType) ^ Objects.hashCode(rawType);
    }

    @Override
    public String getTypeName() {
        return toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (ownerType != null) {
            sb.append(ownerType.getTypeName()).append('$').append(rawType.getSimpleName());
        } else {
            sb.append(rawType.getName());
        }
        if (actualTypeArguments.length > 0) {
            StringJoiner joiner = new StringJoiner(", ", "<", ">");
            for (Type argument : actualTypeArguments) {
                joiner.add(argument.getTypeName());
            }
            sb.append(joiner);
        }
        return sb.toString();
    }
}
