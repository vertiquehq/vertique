// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.contract;

import dev.vertique.rest.jaxrs.publication.InputKey;
import java.util.Set;

/**
 * One operation a mount routes, as the checks of a served contract see it.
 *
 * <p>Internal to the OpenAPI documentation module; not an application API.
 *
 * @param operationId the operation's id
 * @param httpMethod the HTTP method the route answers
 * @param renderedPath the operation's mount-relative path template, rendered as a document path key
 * @param hidden whether the operation is hidden from documents
 * @param hiddenInputs the inputs of the operation that are hidden from documents
 */
public record RoutedOperation(
        String operationId, String httpMethod, String renderedPath, boolean hidden, Set<InputKey> hiddenInputs) {}
