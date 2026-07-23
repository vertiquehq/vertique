// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import java.util.List;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeMirror;

/**
 * APT-side model for a single discoverable method in a {@code @RestClient} interface.
 *
 * <p>Produced by {@link dev.vertique.codegen.rest.client.processor.scan.ClientInterfaceScanner}
 * for each non-{@code default} method that carries a supported HTTP verb annotation.
 *
 * @param method the source method element
 * @param httpVerb the HTTP verb string (e.g. {@code "GET"}, {@code "POST"})
 * @param pathSuffix the method-level {@code @Path} value, or empty string if absent
 * @param params the ordered list of parameter models; includes bean fields when expanded
 * @param returnType the full return type mirror (e.g. {@code Future<User>})
 * @param futureValueType the type argument unwrapped from {@code Future<T>}, or the raw
 *     return type if not a {@code Future}
 */
public record MethodModel(
        ExecutableElement method,
        String httpVerb,
        String pathSuffix,
        List<ParamModel> params,
        TypeMirror returnType,
        TypeMirror futureValueType) {}
