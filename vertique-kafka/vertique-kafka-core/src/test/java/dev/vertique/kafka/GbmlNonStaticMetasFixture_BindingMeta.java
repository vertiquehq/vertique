// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import java.util.List;

/**
 * Malformed companion for {@link GbmlNonStaticMetasFixture}: the {@code METAS} field is declared
 * as a non-static instance field rather than the required {@code public static final} form.
 *
 * <p>Used to verify that {@link GeneratedBindingMetaLoader#load(Class)} detects the missing
 * {@code static} modifier and throws {@link KafkaRegistrationException} with a clear "malformed"
 * message, rather than propagating a raw {@code NullPointerException} or
 * {@code IllegalArgumentException} from {@code Field.get(null)}.
 */
public final class GbmlNonStaticMetasFixture_BindingMeta {

    /**
     * Intentionally non-static {@code METAS} field — simulates a malformed generated companion.
     *
     * <p>A real companion always declares {@code public static final List<KafkaBindingMeta> METAS}.
     * This instance field is present so {@link Class#getField(String)} succeeds (the field is
     * public), but the static-modifier check in the loader must fire before {@code get(null)} is
     * called.
     */
    @SuppressWarnings("rawtypes")
    public final List METAS = List.of();

    /**
     * Default constructor required so {@link Class#getField(String)} can find the public instance
     * field via reflection.
     */
    public GbmlNonStaticMetasFixture_BindingMeta() {}
}
