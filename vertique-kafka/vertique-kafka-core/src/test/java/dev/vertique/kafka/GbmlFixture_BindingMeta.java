// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import java.util.List;

/**
 * Hand-written companion for {@link GbmlFixture}, simulating a well-formed generated
 * {@code _BindingMeta} companion class for use in {@link GeneratedBindingMetaLoaderTest}.
 *
 * <p>Exposes a {@code METAS} field containing one SOURCE entry and one ROUTER entry so that both
 * conversion paths can be exercised in the same fixture.
 */
public final class GbmlFixture_BindingMeta {

    /** Precomputed binding metadata: one SOURCE entry (Model 1) and one ROUTER entry (Model 3). */
    public static final List<KafkaBindingMeta> METAS = List.of(
            new KafkaBindingMeta(
                    "fixture-svc-handle",
                    "fixture.events",
                    "fixture-grp",
                    KafkaBindingMeta.Kind.SOURCE,
                    null, // valueType null for SOURCE — resolved at runtime from ServiceMethodMeta
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null, // jsonProfile null for SOURCE (@KafkaSource has no profile)
                    "handle", // targetOperation = impl method name
                    List.of()),
            new KafkaBindingMeta(
                    "fixture-router",
                    "fixture.router.events",
                    "fixture-router-grp",
                    KafkaBindingMeta.Kind.ROUTER,
                    null,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null,
                    null,
                    List.of(new KafkaBindingMeta.RouteMeta(
                            "event-type", "", "created", false, String.class, null, null))));

    private GbmlFixture_BindingMeta() {}
}
