// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

/**
 * Malformed companion for {@link GbmlMissingMetasFixture}: the companion class exists but has no
 * {@code METAS} field. Used to verify that {@link GeneratedBindingMetaLoader#load(Class)} fails
 * loudly (not silently falls back) when the companion is present but malformed.
 */
public final class GbmlMissingMetasFixture_BindingMeta {

    /** Intentionally omitted {@code METAS} field — this companion is malformed by design. */
    private GbmlMissingMetasFixture_BindingMeta() {}
}
