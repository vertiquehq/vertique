// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Objects;

/**
 * Default implementation of {@link WorkflowActorMapper} that translates a {@link SecurityIdentity}
 * into a {@link WorkflowActor} by switching on {@link dev.vertique.security.PrincipalType}.
 *
 * <p>Mapping rules:
 * <ul>
 *   <li>{@link PrincipalType#USER} → {@link WorkflowActor.User} with the actor's id</li>
 *   <li>{@link PrincipalType#SERVICE} → {@link WorkflowActor.Service} with the actor's id</li>
 *   <li>{@link PrincipalType#SYSTEM} → {@link WorkflowActor.System} using the
 *       {@code system.reason} attribute when present, falling back to
 *       {@code "unspecified"} when the attribute is absent</li>
 *   <li>{@link PrincipalType#ANONYMOUS} → {@link WorkflowActor.Anonymous}</li>
 * </ul>
 *
 * <p>Used as the {@link WorkflowActorMapper} when the application binds none; see
 * {@link WorkflowActorMapper#orDefault(java.util.Optional)}.
 */
@Singleton
public final class DefaultWorkflowActorMapper implements WorkflowActorMapper {

    /**
     * Creates a new {@code DefaultWorkflowActorMapper}.
     */
    @Inject
    public DefaultWorkflowActorMapper() {}

    /**
     * Maps the given {@link SecurityIdentity} to a {@link WorkflowActor}.
     *
     * <p>Maps {@code USER} → {@link WorkflowActor.User}, {@code SERVICE} →
     * {@link WorkflowActor.Service}, {@code SYSTEM} → {@link WorkflowActor.System}
     * (using {@code system.reason} attribute or {@code "unspecified"} fallback).
     * {@code ANONYMOUS} → {@link WorkflowActor.Anonymous}.
     *
     * @param identity the security identity to map; must not be {@code null}
     * @return the corresponding workflow actor; never {@code null}
     * @throws NullPointerException     if {@code identity} is {@code null}
     */
    @Override
    public WorkflowActor toWorkflowActor(SecurityIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return switch (identity.actor().type()) {
            case USER -> new WorkflowActor.User(identity.actor().id());
            case SERVICE -> new WorkflowActor.Service(identity.actor().id());
            case SYSTEM -> {
                String reason = (String) identity.actor().attributes().getOrDefault("system.reason", "unspecified");
                yield new WorkflowActor.System(reason);
            }
            case ANONYMOUS -> new WorkflowActor.Anonymous();
        };
    }
}
