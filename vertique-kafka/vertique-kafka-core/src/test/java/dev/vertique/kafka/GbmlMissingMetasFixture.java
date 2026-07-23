// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

/**
 * Origin class whose companion ({@link GbmlMissingMetasFixture_BindingMeta}) exists but lacks
 * the required {@code METAS} field. Used to verify that {@link GeneratedBindingMetaLoader#load(Class)}
 * fails loudly when the companion is present but missing the expected field.
 */
public final class GbmlMissingMetasFixture {}
