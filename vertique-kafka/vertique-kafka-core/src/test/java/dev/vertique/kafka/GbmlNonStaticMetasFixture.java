// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

/**
 * Marker class for the Fix-3 non-static {@code METAS} field fixture.
 *
 * <p>Its companion {@link GbmlNonStaticMetasFixture_BindingMeta} has a {@code METAS} field that is
 * declared as an <em>instance</em> field (not {@code static}). This verifies that
 * {@link GeneratedBindingMetaLoader#load(Class)} throws {@link KafkaRegistrationException} with a
 * clear "malformed" message instead of producing a {@code NullPointerException} or
 * {@code IllegalArgumentException} from {@code metasField.get(null)}.
 */
public final class GbmlNonStaticMetasFixture {

    private GbmlNonStaticMetasFixture() {}
}
