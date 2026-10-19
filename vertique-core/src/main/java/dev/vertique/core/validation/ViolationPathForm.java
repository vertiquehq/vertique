// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

/**
 * How the detail paths of a {@link ViolationCarrier} are written.
 *
 * <p>A transport reads the form to decide whether a path may be reported as a location. New values
 * may be added in a later release, so a consumer that switches on this type keeps a default branch
 * and treats a value it does not recognize like {@link #JAVA_PROPERTY_PATH}.
 *
 * <p>A carrier's pointer is relative to the document its producer validated and is built with the
 * producer's own mapper. It is not guaranteed to be a pointer into the body of the request that
 * carried the data, for example when the route binds names differently or nests the validated
 * document.
 */
public enum ViolationPathForm {

    /**
     * A Java property path of the validated object, such as {@code items[0].name}. It is not a
     * location in any request and a transport does not render it as one.
     */
    JAVA_PROPERTY_PATH,

    /**
     * An RFC 6901 JSON Pointer into the document the producer validated, written with the names its
     * mapper uses. A producer that declares this form guarantees that every detail path is either
     * {@code ""} (the whole document) or begins with {@code /}, and that no path contains a key the
     * client chose.
     */
    JSON_POINTER
}
