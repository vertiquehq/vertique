// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code List<T>} record field whose external JSON is a keyed object {@code {key:{...}}};
 * the key is injected into property {@link #value()} of each {@code T} during deserialization.
 *
 * <p>The annotated field's element type {@code T} must declare a property matching {@link #value()}
 * (e.g. a record component or a settable JSON property). For each entry of the keyed object, the
 * map key becomes the value of that property unless the entry's JSON already sets it to the same
 * value; a conflicting explicit value is rejected.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface KeyedBy {

    /**
     * The identity property name on the element type {@code T} into which each entry's key is
     * injected.
     *
     * @return the identity property name
     */
    String value();
}
