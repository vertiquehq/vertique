// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.rest.client.processor;

import java.util.List;
import java.util.Set;
import javax.lang.model.element.TypeElement;

/**
 * APT-side model for a single {@code @RestClient}-annotated interface.
 *
 * <p>Produced by {@link dev.vertique.codegen.rest.client.processor.scan.ClientInterfaceScanner}
 * and consumed by the validators and emitters.
 *
 * @param clientType the type element representing the interface (e.g. {@code UserClient})
 * @param basePath the class-level {@code @Path} value, or empty string if absent
 * @param methods the ordered list of method models for all discoverable methods
 * @param referencedBeans the set of {@code @BeanParam} type elements referenced from any method
 *     in this interface; used by {@link dev.vertique.codegen.rest.client.processor.emit.BeanAccessorEmitter}
 *     for deduplication across the compilation round
 */
public record ClientInterfaceModel(
        TypeElement clientType, String basePath, List<MethodModel> methods, Set<TypeElement> referencedBeans) {}
