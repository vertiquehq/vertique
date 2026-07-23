// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import java.util.List;

/**
 * Hand-written companion for {@link GbmlDualAnnotationFixture}, simulating a generated
 * {@code _BindingMeta} companion for a class that carries both a {@code @KafkaSource} method
 * (Model 1) and a {@code @KafkaListener} router annotation (Model 3).
 *
 * <p>The {@code METAS} list intentionally mixes {@link KafkaBindingMeta.Kind#SOURCE} and
 * {@link KafkaBindingMeta.Kind#ROUTER} entries to verify that:
 * <ul>
 *   <li>{@code KafkaConsumerScanner.scanKafkaSources} processes only SOURCE metas and silently
 *       skips ROUTER metas in the same companion.</li>
 *   <li>{@code KafkaConsumerScanner.scanListeners} processes only ROUTER metas when the
 *       contribution is a {@code Class<?>}, and only HANDLER metas when the contribution is a
 *       {@code KafkaRecordHandler} instance.</li>
 * </ul>
 */
public final class GbmlDualAnnotationFixture_BindingMeta {

    /**
     * Precomputed binding metadata: one SOURCE entry (Model 1) and one ROUTER entry (Model 3).
     *
     * <p>The SOURCE entry references the operation name {@code "handle"} to exercise the
     * {@link GeneratedBindingMetaLoader#toSourceEntry} path. The ROUTER entry provides a single
     * route so it satisfies the ROUTER invariant (non-empty routes).
     */
    public static final List<KafkaBindingMeta> METAS = List.of(
            new KafkaBindingMeta(
                    "dual-source",
                    "dual.source.events",
                    "dual-grp",
                    KafkaBindingMeta.Kind.SOURCE,
                    null, // valueType null for SOURCE — resolved at runtime from ServiceMethodMeta
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null, // jsonProfile null for SOURCE (@KafkaSource has no profile)
                    "handle", // targetOperation = impl method name
                    List.of()),
            new KafkaBindingMeta(
                    "dual-router",
                    "dual.router.events",
                    "dual-grp",
                    KafkaBindingMeta.Kind.ROUTER,
                    null,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null,
                    null,
                    List.of(new KafkaBindingMeta.RouteMeta(
                            "event-type", "", "created", false, String.class, null, null))));

    private GbmlDualAnnotationFixture_BindingMeta() {}
}
