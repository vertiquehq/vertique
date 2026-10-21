// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.registry.store;

import java.util.Objects;
import java.util.UUID;

/** One fenced publication attempt; an obsolete token cannot complete or release another attempt. */
public record ActivationClaim(DefinitionKey key, UUID token) {
    public ActivationClaim {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(token, "token");
    }
}
