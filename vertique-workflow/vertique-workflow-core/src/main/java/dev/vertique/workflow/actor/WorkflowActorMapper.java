// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import dev.vertique.security.SecurityIdentity;
import java.util.Objects;
import java.util.Optional;

/**
 * SPI for mapping a {@link SecurityIdentity} to a {@link WorkflowActor}.
 *
 * <p>Provides the bridge between the framework's identity model and the workflow audit identity
 * model. Every mutating workflow task operation requires a {@link WorkflowActor} so that the
 * append-only workflow history can answer "who initiated this change?".
 *
 * <p>The default implementation is {@link DefaultWorkflowActorMapper}. Applications may provide
 * a custom binding to remap identities differently (e.g., when a SERVICE principal should map to
 * a specific {@link WorkflowActor.System} reason rather than a generic service id).
 *
 * <p>{@code WorkflowCoreModule} declares this type with {@code @BindsOptionalOf}, so an application
 * binding is optional. A consumer injects {@code Optional<WorkflowActorMapper>} and resolves it with
 * {@link #orDefault(Optional)}, which returns the application's binding or the default.
 *
 * @see WorkflowActor
 * @see DefaultWorkflowActorMapper
 */
public interface WorkflowActorMapper {

    /**
     * Maps the given {@link SecurityIdentity} to a {@link WorkflowActor}.
     *
     * @param identity the security identity to map; must not be {@code null}
     * @return the corresponding workflow actor; never {@code null}
     * @throws NullPointerException     if {@code identity} is {@code null}
     * @throws IllegalArgumentException if the identity cannot be mapped to a workflow actor
     *                                  (e.g., an {@code ANONYMOUS} identity that the application
     *                                  does not permit to initiate workflow operations)
     */
    WorkflowActor toWorkflowActor(SecurityIdentity identity);

    /**
     * Returns the application's mapper when it binds one, the {@link DefaultWorkflowActorMapper}
     * otherwise. This is the one place a consumer resolves the optional binding.
     *
     * @param bound the injected optional binding; must not be {@code null}
     * @return the mapper to use; never {@code null}
     * @throws NullPointerException if {@code bound} is {@code null}
     */
    static WorkflowActorMapper orDefault(Optional<WorkflowActorMapper> bound) {
        return Objects.requireNonNull(bound, "bound").orElseGet(DefaultWorkflowActorMapper::new);
    }
}
