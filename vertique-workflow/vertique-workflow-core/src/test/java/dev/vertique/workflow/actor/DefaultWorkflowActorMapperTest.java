// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SystemIdentities;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultWorkflowActorMapper}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>USER identity → {@link WorkflowActor.User} with correct id</li>
 *   <li>SERVICE identity → {@link WorkflowActor.Service} with correct id</li>
 *   <li>SYSTEM identity with {@code system.reason} attribute → {@link WorkflowActor.System}
 *       with that reason</li>
 *   <li>SYSTEM identity without {@code system.reason} attribute → {@link WorkflowActor.System}
 *       with fallback reason {@code "unspecified"}</li>
 *   <li>ANONYMOUS identity → {@link IllegalArgumentException}</li>
 *   <li>Null input → {@link NullPointerException}</li>
 * </ul>
 */
class DefaultWorkflowActorMapperTest {

    private final DefaultWorkflowActorMapper mapper = new DefaultWorkflowActorMapper();

    // --- USER ---

    @Nested
    @DisplayName("USER identity")
    class UserIdentity {

        @Test
        @DisplayName("maps to WorkflowActor.User with the actor id")
        void mapsToUser() {
            SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "alice", Map.of()));

            WorkflowActor actor = mapper.toWorkflowActor(identity);

            assertThat(actor).isInstanceOf(WorkflowActor.User.class);
            assertThat(((WorkflowActor.User) actor).userId()).isEqualTo("alice");
        }

        @Test
        @DisplayName("preserves the actor id exactly")
        void preservesActorId() {
            SecurityIdentity identity =
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-uuid-123", Map.of()));

            WorkflowActor.User result = (WorkflowActor.User) mapper.toWorkflowActor(identity);

            assertThat(result.userId()).isEqualTo("user-uuid-123");
        }
    }

    // --- SERVICE ---

    @Nested
    @DisplayName("SERVICE identity")
    class ServiceIdentity {

        @Test
        @DisplayName("maps to WorkflowActor.Service with the actor id")
        void mapsToService() {
            SecurityIdentity identity =
                    SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "payment-svc", Map.of()));

            WorkflowActor actor = mapper.toWorkflowActor(identity);

            assertThat(actor).isInstanceOf(WorkflowActor.Service.class);
            assertThat(((WorkflowActor.Service) actor).serviceId()).isEqualTo("payment-svc");
        }

        @Test
        @DisplayName("preserves the actor id exactly")
        void preservesActorId() {
            SecurityIdentity identity =
                    SecurityIdentity.service(new PrincipalRef(PrincipalType.SERVICE, "svc:billing", Map.of()));

            WorkflowActor.Service result = (WorkflowActor.Service) mapper.toWorkflowActor(identity);

            assertThat(result.serviceId()).isEqualTo("svc:billing");
        }
    }

    // --- SYSTEM ---

    @Nested
    @DisplayName("SYSTEM identity")
    class SystemIdentity {

        @Test
        @DisplayName("maps to WorkflowActor.System using system.reason attribute when present")
        void mapsWithReason() {
            SecurityIdentity identity = SystemIdentities.workflow("due-date-expired");

            WorkflowActor actor = mapper.toWorkflowActor(identity);

            assertThat(actor).isInstanceOf(WorkflowActor.System.class);
            assertThat(((WorkflowActor.System) actor).reason()).isEqualTo("due-date-expired");
        }

        @Test
        @DisplayName("maps to WorkflowActor.System with 'unspecified' when system.reason is absent")
        void fallbackWhenReasonAbsent() {
            // Build a SYSTEM identity without the system.reason attribute
            PrincipalRef systemActor = new PrincipalRef(PrincipalType.SYSTEM, "system:custom", Map.of());
            SecurityIdentity identity =
                    new SecurityIdentity(systemActor, Optional.empty(), Optional.empty(), Optional.empty());

            WorkflowActor actor = mapper.toWorkflowActor(identity);

            assertThat(actor).isInstanceOf(WorkflowActor.System.class);
            assertThat(((WorkflowActor.System) actor).reason()).isEqualTo("unspecified");
        }

        @Test
        @DisplayName("scheduledJob identity maps with the job reason")
        void scheduledJobReason() {
            SecurityIdentity identity = SystemIdentities.scheduledJob("nightly-cleanup");

            WorkflowActor.System result = (WorkflowActor.System) mapper.toWorkflowActor(identity);

            assertThat(result.reason()).isEqualTo("nightly-cleanup");
        }
    }

    // --- ANONYMOUS ---

    @Nested
    @DisplayName("ANONYMOUS identity")
    class AnonymousIdentity {

        @Test
        @DisplayName("throws IllegalArgumentException — workflow requires a named actor")
        void throwsForAnonymous() {
            SecurityIdentity anonymous = SecurityIdentity.anonymous();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> mapper.toWorkflowActor(anonymous))
                    .withMessageContaining("ANONYMOUS");
        }
    }

    // --- null guard ---

    @Test
    @DisplayName("null identity throws NullPointerException")
    void nullThrows() {
        assertThatNullPointerException()
                .isThrownBy(() -> mapper.toWorkflowActor(null))
                .withMessageContaining("identity");
    }
}
