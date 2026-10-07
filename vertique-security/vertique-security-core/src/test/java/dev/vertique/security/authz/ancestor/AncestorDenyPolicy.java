// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz.ancestor;

import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.DenyAll;

/** Restrictive policy visible only so an inaccessible ancestor method can carry it. */
@DenyAll
public interface AncestorDenyPolicy extends AccessPolicy {}
