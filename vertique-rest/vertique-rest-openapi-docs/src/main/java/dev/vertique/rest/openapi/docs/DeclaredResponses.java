// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.core.RestConfigurationException;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.annotation.Nullable;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Reads the {@link ApiResponse}s an operation declares, merged from its method and its class.
 *
 * <p>The method level is every resolved annotation of the operation method (the method itself, the
 * methods it overrides, and the interface methods it implements); the class level is every resolved
 * annotation of its class. At each level the repeated {@link ApiResponse} form and the {@link
 * ApiResponses} container are equivalent. A status both levels declare takes the method's
 * declaration. {@code @Operation.responses} is not read.
 */
final class DeclaredResponses {

    private DeclaredResponses() {}

    /**
     * Reads the declared responses of one operation.
     *
     * @param prefix the failure-message prefix naming the document's configuration path, the
     *     application, and its mount
     * @param operationId the runtime id of the operation
     * @param facts the operation's descriptor facts, or {@code null} when none were taken
     * @return the declared responses keyed by status, in published order; empty when none is declared
     * @throws RestConfigurationException when a status is not a valid key, or one level declares a
     *     status more than once
     */
    static SortedMap<String, ApiResponse> read(String prefix, String operationId, @Nullable OperationFacts facts) {
        if (facts == null) {
            return Collections.emptySortedMap();
        }
        SortedMap<String, ApiResponse> method = level(prefix, operationId, facts.methodAnnotations(), "method");
        SortedMap<String, ApiResponse> type = level(prefix, operationId, facts.classAnnotations(), "class");
        SortedMap<String, ApiResponse> merged = new TreeMap<>(ResponseStatuses.ORDER);
        merged.putAll(type);
        merged.putAll(method);
        return Collections.unmodifiableSortedMap(merged);
    }

    /** Reads and checks the responses one level declares. */
    private static SortedMap<String, ApiResponse> level(
            String prefix, String operationId, List<Annotation> annotations, String level) {
        SortedMap<String, ApiResponse> declared = new TreeMap<>(ResponseStatuses.ORDER);
        for (ApiResponse response : responses(annotations)) {
            String status = response.responseCode();
            if (!ResponseStatuses.isValid(status)) {
                throw new RestConfigurationException(prefix + ": operation '" + operationId
                        + "' declares the response status '" + status + "', which is neither 'default', a"
                        + " three-digit code from 100 to 599, nor a range key from 1XX to 5XX");
            }
            if (declared.putIfAbsent(status, response) != null) {
                throw new RestConfigurationException(prefix + ": operation '" + operationId
                        + "' declares the response status " + status + " more than once on its " + level
                        + "; declare each status once per method and once per class");
            }
        }
        return declared;
    }

    /** Returns the {@link ApiResponse}s among annotations, in order, each container unwrapped in place. */
    private static List<ApiResponse> responses(List<Annotation> annotations) {
        List<ApiResponse> responses = new ArrayList<>();
        for (Annotation annotation : annotations) {
            if (annotation instanceof ApiResponse response) {
                responses.add(response);
            } else if (annotation instanceof ApiResponses container) {
                responses.addAll(List.of(container.value()));
            }
        }
        return responses;
    }
}
