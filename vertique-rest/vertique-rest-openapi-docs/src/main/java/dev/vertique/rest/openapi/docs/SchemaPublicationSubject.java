// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.jaxrs.routing.ParamLocation;
import java.util.Locale;

/**
 * Names what one schema a document publishes describes: an input of an operation (the request body,
 * a parameter by location and name, or a form field by name) or one of its response schemas (a
 * content or header schema of a status).
 *
 * <p>It gives the schema's component key and the phrases failure messages use to name the schema's
 * subject. A component key is {@code <operationId>.request} for the request body, {@code
 * <operationId>.<location>.<name>} for a parameter (location in lowercase), {@code
 * <operationId>.form.<name>} for a form field, and the operation id followed by the caller's suffix
 * for a response schema, with every character outside {@code [A-Za-z0-9._-]} replaced by {@code _}.
 *
 * @param operationId the runtime id of the operation
 * @param noun the subject as a failure message names it, for example {@code query parameter 'code'}
 * @param keySuffix the part of the component key after the operation id, before replacement
 * @param alwaysComponent whether the schema is always published as a component, as a request body or
 *     a response schema is, rather than only when it holds a reference or definitions; such a subject's
 *     noun is a common noun, so a collision phrase gives it the article {@code the}
 */
record SchemaPublicationSubject(String operationId, String noun, String keySuffix, boolean alwaysComponent) {

    /**
     * Names the request body of an operation.
     *
     * @param operationId the runtime id of the operation
     * @return the subject naming the body, always published as a component
     */
    static SchemaPublicationSubject body(String operationId) {
        return new SchemaPublicationSubject(operationId, "request body", ".request", true);
    }

    /**
     * Names a response schema of an operation, published as a component the way a request body is.
     *
     * @param operationId the runtime id of the operation
     * @param noun the response schema as a failure message names it, for example {@code output schema
     *     of status 200}
     * @param keySuffix the part of the component key after the operation id, before replacement
     * @return the subject naming the response schema, always published as a component
     */
    static SchemaPublicationSubject output(String operationId, String noun, String keySuffix) {
        return new SchemaPublicationSubject(operationId, noun, keySuffix, true);
    }

    /**
     * Names a parameter of an operation.
     *
     * @param operationId the runtime id of the operation
     * @param location the parameter's location
     * @param name the parameter's name
     * @return the subject naming the parameter
     */
    static SchemaPublicationSubject parameter(String operationId, ParamLocation location, String name) {
        String in = location.name().toLowerCase(Locale.ROOT);
        return new SchemaPublicationSubject(
                operationId, in + " parameter '" + name + "'", "." + in + "." + name, false);
    }

    /**
     * Names a form field of an operation.
     *
     * @param operationId the runtime id of the operation
     * @param name the form field's name
     * @return the subject naming the form field
     */
    static SchemaPublicationSubject formField(String operationId, String name) {
        return new SchemaPublicationSubject(operationId, "form field '" + name + "'", ".form." + name, false);
    }

    /**
     * Returns the key of the component this subject's schema is published as.
     *
     * @return the component key, every character outside {@code [A-Za-z0-9._-]} replaced by {@code _}
     */
    String componentKey() {
        return componentKey(operationId + keySuffix);
    }

    /**
     * Returns how a refused construct names this subject.
     *
     * @return {@code the <noun> of operation '<id>'}
     */
    String refusalPhrase() {
        return "the " + noun + " of operation '" + operationId + "'";
    }

    /**
     * Returns how a component collision names this subject.
     *
     * @return {@code the <noun> of operation '<id>'} for a subject always published as a component,
     *     for example the request body, else {@code <noun> of operation '<id>'}
     */
    String collisionPhrase() {
        return (alwaysComponent ? "the " : "") + noun + " of operation '" + operationId + "'";
    }

    /**
     * Replaces every character (code point) of a candidate component key outside {@code
     * [A-Za-z0-9._-]} with one {@code _}.
     *
     * @param candidate the candidate key
     * @return the component key
     */
    static String componentKey(String candidate) {
        StringBuilder key = new StringBuilder(candidate.length());
        candidate.codePoints().forEach(c -> {
            boolean kept = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '-';
            key.append(kept ? (char) c : '_');
        });
        return key.toString();
    }
}
