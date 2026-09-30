// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication;

import java.lang.reflect.Type;
import java.util.List;

/**
 * INTERNAL: the response facts of one operation's resource method. Public only for cross-module use
 * by sibling framework modules; outside the maturity promise and not an application contract.
 *
 * @param genericReturnType the method's generic return type
 * @param resourceClass     the resource instance's class, for resolving type variables against its
 *                          generic supertypes
 * @param returnsFuture     whether the method returns a {@code Future}
 * @param returnsVoid       whether the method returns {@code void}
 * @param produces          the media types the method declares it produces
 * @param outputProfileId   the resolved JSON mapper profile id that serializes the response
 */
public record ResponseShape(
        Type genericReturnType,
        Class<?> resourceClass,
        boolean returnsFuture,
        boolean returnsVoid,
        List<String> produces,
        String outputProfileId) {

    /**
     * Compact constructor storing an unmodifiable copy of {@code produces}.
     */
    public ResponseShape {
        produces = List.copyOf(produces);
    }
}
