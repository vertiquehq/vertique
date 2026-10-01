// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.jaxrs.routing.ParamLocation;
import java.util.Locale;

/**
 * Names one input of an operation whose captured schema a document publishes: the request body, a
 * parameter by location and name, or a form field by name.
 *
 * <p>It gives the input's component key and the phrases failure messages use. A component key is
 * {@code <operationId>.request} for the body, {@code <operationId>.<location>.<name>} for a parameter
 * (location in lowercase), and {@code <operationId>.form.<name>} for a form field, with every character
 * outside {@code [A-Za-z0-9._-]} replaced by {@code _}.
 *
 * @param operationId the runtime id of the operation
 * @param noun the input as a failure message names it, for example {@code query parameter 'code'}
 * @param keySuffix the part of the component key after the operation id, before replacement
 * @param body whether the input is the request body
 */
record InputDescription(String operationId, String noun, String keySuffix, boolean body) {

    /**
     * Describes the request body of an operation.
     *
     * @param operationId the runtime id of the operation
     * @return the description of the body
     */
    static InputDescription body(String operationId) {
        return new InputDescription(operationId, "request body", ".request", true);
    }

    /**
     * Describes a response body of an operation, published as a component the way a request body is.
     *
     * @param operationId the runtime id of the operation
     * @param noun the output as a failure message names it, for example {@code response body}
     * @param keySuffix the part of the component key after the operation id, before replacement
     * @return the description of the output, with body semantics
     */
    static InputDescription output(String operationId, String noun, String keySuffix) {
        return new InputDescription(operationId, noun, keySuffix, true);
    }

    /**
     * Describes a parameter of an operation.
     *
     * @param operationId the runtime id of the operation
     * @param location the parameter's location
     * @param name the parameter's name
     * @return the description of the parameter
     */
    static InputDescription parameter(String operationId, ParamLocation location, String name) {
        String in = location.name().toLowerCase(Locale.ROOT);
        return new InputDescription(operationId, in + " parameter '" + name + "'", "." + in + "." + name, false);
    }

    /**
     * Describes a form field of an operation.
     *
     * @param operationId the runtime id of the operation
     * @param name the form field's name
     * @return the description of the form field
     */
    static InputDescription formField(String operationId, String name) {
        return new InputDescription(operationId, "form field '" + name + "'", ".form." + name, false);
    }

    /**
     * Returns the key of the component this input's schema is published as.
     *
     * @return the component key, every character outside {@code [A-Za-z0-9._-]} replaced by {@code _}
     */
    String componentKey() {
        return componentKey(operationId + keySuffix);
    }

    /**
     * Returns how a refused construct names this input.
     *
     * @return {@code the <input> of operation '<id>'}
     */
    String refusalPhrase() {
        return "the " + noun + " of operation '" + operationId + "'";
    }

    /**
     * Returns how a component collision names this input.
     *
     * @return {@code the request body of operation '<id>'} for the body, else {@code <input> of
     *     operation '<id>'}
     */
    String collisionPhrase() {
        return (body ? "the " : "") + noun + " of operation '" + operationId + "'";
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
