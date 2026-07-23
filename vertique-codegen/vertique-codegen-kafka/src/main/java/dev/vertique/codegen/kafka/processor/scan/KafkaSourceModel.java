// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.kafka.processor.scan;

import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * Immutable model of a service implementation class that carries one or more
 * {@link dev.vertique.kafka.KafkaSource @KafkaSource}-annotated methods.
 *
 * <p>One {@code KafkaSourceModel} is produced per implementation class; its
 * {@link #methods()} list has one {@link KafkaSourceMethodModel} per annotated method. The
 * generated {@code {ImplClass}_BindingMeta} companion will expose a {@code METAS} list with one
 * {@link dev.vertique.kafka.KafkaBindingMeta KafkaBindingMeta} per method model entry.
 *
 * @param implType the service-implementation class element that carries the annotated methods
 * @param methods  one entry per {@code @KafkaSource}-annotated method on {@code implType};
 *                 never empty after validation (classes with no valid methods are not emitted)
 */
public record KafkaSourceModel(TypeElement implType, List<KafkaSourceMethodModel> methods) {

    /**
     * Compact constructor that defensively copies the methods list.
     *
     * @param implType the implementation class element
     * @param methods  the list of per-method models; must not be {@code null}
     */
    public KafkaSourceModel {
        methods = List.copyOf(methods);
    }
}
