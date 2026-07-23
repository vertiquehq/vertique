// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import java.util.List;

/**
 * Malformed companion for {@link GbmlWrongTypeFixture}: the {@code METAS} field contains a
 * {@code List<String>} instead of {@code List<KafkaBindingMeta>}, so the loader must throw
 * loudly when it finds a non-{@link KafkaBindingMeta} element.
 */
public final class GbmlWrongTypeFixture_BindingMeta {

    /**
     * Intentionally wrong element type — simulates a corrupted or stale companion class.
     */
    @SuppressWarnings("rawtypes")
    public static final List METAS = List.of("not-a-KafkaBindingMeta");

    private GbmlWrongTypeFixture_BindingMeta() {}
}
