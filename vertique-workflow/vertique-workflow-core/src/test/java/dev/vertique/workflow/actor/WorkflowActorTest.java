// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.vertx.core.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the three {@link WorkflowActor} permits ({@link WorkflowActor.User},
 * {@link WorkflowActor.Service}, {@link WorkflowActor.System}): null/blank rejection, happy-path
 * construction, equality/hashCode round-trips, sealed-sum exhaustiveness via a {@code switch}
 * expression, and Jackson round-trip/shape-pin behavior (ADR-0048).
 */
class WorkflowActorTest {

    // --- User permit ---

    @Nested
    @DisplayName("WorkflowActor.User")
    class UserPermit {

        @Test
        @DisplayName("null userId throws NullPointerException mentioning 'userId'")
        void nullRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new WorkflowActor.User(null))
                    .withMessageContaining("userId");
        }

        @Test
        @DisplayName("blank userId throws IllegalArgumentException mentioning 'must not be blank'")
        void blankRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WorkflowActor.User("   "))
                    .withMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("empty userId throws IllegalArgumentException")
        void emptyRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> new WorkflowActor.User(""));
        }

        @Test
        @DisplayName("happy path constructs and round-trips equality + hashCode")
        void happyPath() {
            WorkflowActor.User a = new WorkflowActor.User("alice");
            WorkflowActor.User b = new WorkflowActor.User("alice");

            assertThat(a.userId()).isEqualTo("alice");
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    // --- Service permit ---

    @Nested
    @DisplayName("WorkflowActor.Service")
    class ServicePermit {

        @Test
        @DisplayName("null serviceId throws NullPointerException mentioning 'serviceId'")
        void nullRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new WorkflowActor.Service(null))
                    .withMessageContaining("serviceId");
        }

        @Test
        @DisplayName("blank serviceId throws IllegalArgumentException mentioning 'must not be blank'")
        void blankRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WorkflowActor.Service("  "))
                    .withMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("happy path constructs and round-trips equality + hashCode")
        void happyPath() {
            WorkflowActor.Service a = new WorkflowActor.Service("payment-svc");
            WorkflowActor.Service b = new WorkflowActor.Service("payment-svc");

            assertThat(a.serviceId()).isEqualTo("payment-svc");
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    // --- System permit ---

    @Nested
    @DisplayName("WorkflowActor.System")
    class SystemPermit {

        @Test
        @DisplayName("null reason throws NullPointerException mentioning 'reason'")
        void nullRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new WorkflowActor.System(null))
                    .withMessageContaining("reason");
        }

        @Test
        @DisplayName("blank reason throws IllegalArgumentException mentioning 'must not be blank'")
        void blankRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new WorkflowActor.System("\t"))
                    .withMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("happy path constructs and round-trips equality + hashCode")
        void happyPath() {
            WorkflowActor.System a = new WorkflowActor.System("due-date-expired");
            WorkflowActor.System b = new WorkflowActor.System("due-date-expired");

            assertThat(a.reason()).isEqualTo("due-date-expired");
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    // --- Sealed-sum exhaustiveness ---

    @Nested
    @DisplayName("sealed-sum exhaustiveness")
    class SealedSumExhaustiveness {

        @Test
        @DisplayName("switch expression covers all three permits without default")
        void switchCoversAllPermits() {
            WorkflowActor user = new WorkflowActor.User("alice");
            WorkflowActor service = new WorkflowActor.Service("svc");
            WorkflowActor system = new WorkflowActor.System("reason");

            assertThat(label(user)).isEqualTo("user:alice");
            assertThat(label(service)).isEqualTo("service:svc");
            assertThat(label(system)).isEqualTo("system:reason");
        }

        private static String label(WorkflowActor actor) {
            return switch (actor) {
                case WorkflowActor.User u -> "user:" + u.userId();
                case WorkflowActor.Service s -> "service:" + s.serviceId();
                case WorkflowActor.System sys -> "system:" + sys.reason();
            };
        }
    }

    // --- Jackson serialization shape pin (ADR-0048: byte-identical wire shape) ---

    @Nested
    @DisplayName("Jackson serialization shape pin")
    class SerializationShapePin {

        @Test
        @DisplayName("User serializes to exactly {\"userId\":\"...\"} — no type discriminator added")
        void userShapeIsUnwrapped() {
            String json = Json.encode(new WorkflowActor.User("alice"));

            assertThat(json).isEqualTo("{\"userId\":\"alice\"}");
        }

        @Test
        @DisplayName("Service serializes to exactly {\"serviceId\":\"...\"} — no type discriminator added")
        void serviceShapeIsUnwrapped() {
            String json = Json.encode(new WorkflowActor.Service("payment-svc"));

            assertThat(json).isEqualTo("{\"serviceId\":\"payment-svc\"}");
        }

        @Test
        @DisplayName("System serializes to exactly {\"reason\":\"...\"} — no type discriminator added")
        void systemShapeIsUnwrapped() {
            String json = Json.encode(new WorkflowActor.System("due-date-expired"));

            assertThat(json).isEqualTo("{\"reason\":\"due-date-expired\"}");
        }
    }

    // --- Jackson round-trip ---

    @Nested
    @DisplayName("Jackson round-trip")
    class JacksonRoundTrip {

        @Test
        @DisplayName("User round-trips through Json.encode/decodeValue(WorkflowActor.class)")
        void userRoundTrips() {
            WorkflowActor original = new WorkflowActor.User("alice");

            WorkflowActor decoded = Json.decodeValue(Json.encode(original), WorkflowActor.class);

            assertThat(decoded).isInstanceOf(WorkflowActor.User.class).isEqualTo(original);
        }

        @Test
        @DisplayName("Service round-trips through Json.encode/decodeValue(WorkflowActor.class)")
        void serviceRoundTrips() {
            WorkflowActor original = new WorkflowActor.Service("payment-svc");

            WorkflowActor decoded = Json.decodeValue(Json.encode(original), WorkflowActor.class);

            assertThat(decoded).isInstanceOf(WorkflowActor.Service.class).isEqualTo(original);
        }

        @Test
        @DisplayName("System round-trips through Json.encode/decodeValue(WorkflowActor.class)")
        void systemRoundTrips() {
            WorkflowActor original = new WorkflowActor.System("workflow-cancelled");

            WorkflowActor decoded = Json.decodeValue(Json.encode(original), WorkflowActor.class);

            assertThat(decoded).isInstanceOf(WorkflowActor.System.class).isEqualTo(original);
        }

        @Test
        @DisplayName("a hand-written legacy {\"userId\":\"...\"} literal deserializes to WorkflowActor.User")
        void legacyUserLiteralDeserializes() {
            WorkflowActor decoded = Json.decodeValue("{\"userId\":\"bob\"}", WorkflowActor.class);

            assertThat(decoded).isEqualTo(new WorkflowActor.User("bob"));
        }

        @Test
        @DisplayName("a hand-written legacy {\"serviceId\":\"...\"} literal deserializes to WorkflowActor.Service")
        void legacyServiceLiteralDeserializes() {
            WorkflowActor decoded = Json.decodeValue("{\"serviceId\":\"billing-svc\"}", WorkflowActor.class);

            assertThat(decoded).isEqualTo(new WorkflowActor.Service("billing-svc"));
        }

        @Test
        @DisplayName("a hand-written legacy {\"reason\":\"...\"} literal deserializes to WorkflowActor.System")
        void legacySystemLiteralDeserializes() {
            WorkflowActor decoded = Json.decodeValue("{\"reason\":\"due-date-expired\"}", WorkflowActor.class);

            assertThat(decoded).isEqualTo(new WorkflowActor.System("due-date-expired"));
        }
    }
}
