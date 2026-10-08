// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.RolesAllowed;

/** Typed policy requiring the {@code ops} role; the interop identities never carry it. */
@RolesAllowed("ops")
public interface McpToolAdmissionOpsPolicy extends AccessPolicy {}
