// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

/**
 * Marker class representing an origin consumer that carries both {@code @KafkaSource} methods
 * (Model 1) and a {@code @KafkaListener} router (Model 3) — the dual-annotation case.
 *
 * <p>The generated companion is {@link GbmlDualAnnotationFixture_BindingMeta}, which contains a
 * mixed {@code METAS} list with one {@link KafkaBindingMeta.Kind#SOURCE} entry and one
 * {@link KafkaBindingMeta.Kind#ROUTER} entry. This fixture is used by
 * {@code GeneratedBindingMetaLoaderTest} to verify that each scan path (
 * {@link GeneratedBindingMetaLoader#toSourceEntry} and
 * {@link GeneratedBindingMetaLoader#toRouterEntry}) filters by kind and ignores metas that belong
 * to the other model.
 */
public final class GbmlDualAnnotationFixture {}
