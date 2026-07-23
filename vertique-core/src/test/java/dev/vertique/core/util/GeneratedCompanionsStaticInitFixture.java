// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

/**
 * Origin interface for the present-but-fails-to-load path in {@link GeneratedCompanionsTest}: its
 * companion {@link GeneratedCompanionsStaticInitFixture_TestProxy} throws from its static initializer,
 * so an eager {@code Class.forName(initialize=true)} surfaces a {@link LinkageError} at the load step.
 */
public interface GeneratedCompanionsStaticInitFixture {}
