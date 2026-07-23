// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.security.authz.Effect;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Configuration record for a single authorization policy statement.
 *
 * <p>Each statement within a {@link PolicyDefinitionConfig} specifies an {@link Effect} (currently
 * only {@link Effect#ALLOW}) and a list of action patterns (expressed as canonical strings such as
 * {@code "cms.content.read"} or wildcards like {@code "cms.content.*"}).
 *
 * <p>Deserialized from the {@code "authorization.policies[].statements[]"} section of the
 * application config via {@link dev.vertique.core.config.ConfigParser}.
 *
 * @param effect  the effect this statement grants; defaults to {@link Effect#ALLOW} when absent
 * @param actions the list of action pattern strings; must not be {@code null}
 */
public record PolicyStatementConfig(Effect effect, List<String> actions) {

    /**
     * Compact constructor: validates non-null fields and defensively copies the actions list.
     *
     * @throws NullPointerException if {@code effect} or {@code actions} is {@code null}
     */
    public PolicyStatementConfig {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(actions, "actions");
        actions = List.copyOf(actions);
    }

    /**
     * Jackson-friendly factory that fills in defaults for omitted JSON properties.
     *
     * @param effect  the effect; defaults to {@link Effect#ALLOW} when {@code null}
     * @param actions the action patterns; defaults to an empty list when {@code null}
     * @return the deserialized statement config
     */
    @JsonCreator
    public static PolicyStatementConfig fromJson(
            @JsonProperty("effect") @Nullable Effect effect, @JsonProperty("actions") @Nullable List<String> actions) {
        return new PolicyStatementConfig(effect != null ? effect : Effect.ALLOW, actions != null ? actions : List.of());
    }
}
