// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

/**
 * Origin class whose companion ({@link GbmlWrongTypeFixture_BindingMeta}) has a {@code METAS}
 * field of the wrong type. Used to verify that {@link GeneratedBindingMetaLoader#load(Class)}
 * fails loudly rather than silently falling back.
 */
public final class GbmlWrongTypeFixture {}
