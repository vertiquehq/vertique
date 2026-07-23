// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.vertx.core.json.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the three {@link TaskAssignment} permits ({@link TaskAssignment.User},
 * {@link TaskAssignment.Role}, {@link TaskAssignment.Queue}): null/blank rejection, happy-path
 * construction, equality/hashCode round-trips, sealed-sum exhaustiveness via a {@code switch}
 * expression, and Jackson round-trip/shape-pin behavior (ADR-0048).
 */
class TaskAssignmentTest {

    // --- User permit ---

    @Nested
    @DisplayName("TaskAssignment.User")
    class UserPermit {

        @Test
        @DisplayName("null userId throws NullPointerException mentioning 'userId'")
        void nullRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TaskAssignment.User(null))
                    .withMessageContaining("userId");
        }

        @Test
        @DisplayName("blank userId throws IllegalArgumentException mentioning 'must not be blank'")
        void blankRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TaskAssignment.User("   "))
                    .withMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("empty userId throws IllegalArgumentException")
        void emptyRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> new TaskAssignment.User(""));
        }

        @Test
        @DisplayName("happy path constructs and round-trips equality + hashCode")
        void happyPath() {
            TaskAssignment.User a = new TaskAssignment.User("bob");
            TaskAssignment.User b = new TaskAssignment.User("bob");

            assertThat(a.userId()).isEqualTo("bob");
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    // --- Role permit ---

    @Nested
    @DisplayName("TaskAssignment.Role")
    class RolePermit {

        @Test
        @DisplayName("null roleId throws NullPointerException mentioning 'roleId'")
        void nullRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TaskAssignment.Role(null))
                    .withMessageContaining("roleId");
        }

        @Test
        @DisplayName("blank roleId throws IllegalArgumentException mentioning 'must not be blank'")
        void blankRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TaskAssignment.Role("  "))
                    .withMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("happy path constructs and round-trips equality + hashCode")
        void happyPath() {
            TaskAssignment.Role a = new TaskAssignment.Role("compliance");
            TaskAssignment.Role b = new TaskAssignment.Role("compliance");

            assertThat(a.roleId()).isEqualTo("compliance");
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }
    }

    // --- Queue permit ---

    @Nested
    @DisplayName("TaskAssignment.Queue")
    class QueuePermit {

        @Test
        @DisplayName("null queueName throws NullPointerException mentioning 'queueName'")
        void nullRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TaskAssignment.Queue(null))
                    .withMessageContaining("queueName");
        }

        @Test
        @DisplayName("blank queueName throws IllegalArgumentException mentioning 'must not be blank'")
        void blankRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TaskAssignment.Queue("\t"))
                    .withMessageContaining("must not be blank");
        }

        @Test
        @DisplayName("happy path constructs and round-trips equality + hashCode")
        void happyPath() {
            TaskAssignment.Queue a = new TaskAssignment.Queue("review-queue");
            TaskAssignment.Queue b = new TaskAssignment.Queue("review-queue");

            assertThat(a.queueName()).isEqualTo("review-queue");
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
            TaskAssignment user = new TaskAssignment.User("alice");
            TaskAssignment role = new TaskAssignment.Role("admin");
            TaskAssignment queue = new TaskAssignment.Queue("inbox");

            assertThat(label(user)).isEqualTo("user:alice");
            assertThat(label(role)).isEqualTo("role:admin");
            assertThat(label(queue)).isEqualTo("queue:inbox");
        }

        private static String label(TaskAssignment assignment) {
            return switch (assignment) {
                case TaskAssignment.User u -> "user:" + u.userId();
                case TaskAssignment.Role r -> "role:" + r.roleId();
                case TaskAssignment.Queue q -> "queue:" + q.queueName();
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
            String json = Json.encode(new TaskAssignment.User("bob"));

            assertThat(json).isEqualTo("{\"userId\":\"bob\"}");
        }

        @Test
        @DisplayName("Role serializes to exactly {\"roleId\":\"...\"} — no type discriminator added")
        void roleShapeIsUnwrapped() {
            String json = Json.encode(new TaskAssignment.Role("compliance"));

            assertThat(json).isEqualTo("{\"roleId\":\"compliance\"}");
        }

        @Test
        @DisplayName("Queue serializes to exactly {\"queueName\":\"...\"} — no type discriminator added")
        void queueShapeIsUnwrapped() {
            String json = Json.encode(new TaskAssignment.Queue("review-queue"));

            assertThat(json).isEqualTo("{\"queueName\":\"review-queue\"}");
        }
    }

    // --- Jackson round-trip ---

    @Nested
    @DisplayName("Jackson round-trip")
    class JacksonRoundTrip {

        @Test
        @DisplayName("User round-trips through Json.encode/decodeValue(TaskAssignment.class)")
        void userRoundTrips() {
            TaskAssignment original = new TaskAssignment.User("bob");

            TaskAssignment decoded = Json.decodeValue(Json.encode(original), TaskAssignment.class);

            assertThat(decoded).isInstanceOf(TaskAssignment.User.class).isEqualTo(original);
        }

        @Test
        @DisplayName("Role round-trips through Json.encode/decodeValue(TaskAssignment.class)")
        void roleRoundTrips() {
            TaskAssignment original = new TaskAssignment.Role("compliance");

            TaskAssignment decoded = Json.decodeValue(Json.encode(original), TaskAssignment.class);

            assertThat(decoded).isInstanceOf(TaskAssignment.Role.class).isEqualTo(original);
        }

        @Test
        @DisplayName("Queue round-trips through Json.encode/decodeValue(TaskAssignment.class)")
        void queueRoundTrips() {
            TaskAssignment original = new TaskAssignment.Queue("review-queue");

            TaskAssignment decoded = Json.decodeValue(Json.encode(original), TaskAssignment.class);

            assertThat(decoded).isInstanceOf(TaskAssignment.Queue.class).isEqualTo(original);
        }

        @Test
        @DisplayName("a hand-written legacy {\"userId\":\"...\"} literal deserializes to TaskAssignment.User")
        void legacyUserLiteralDeserializes() {
            TaskAssignment decoded = Json.decodeValue("{\"userId\":\"alice\"}", TaskAssignment.class);

            assertThat(decoded).isEqualTo(new TaskAssignment.User("alice"));
        }

        @Test
        @DisplayName("a hand-written legacy {\"roleId\":\"...\"} literal deserializes to TaskAssignment.Role")
        void legacyRoleLiteralDeserializes() {
            TaskAssignment decoded = Json.decodeValue("{\"roleId\":\"admin\"}", TaskAssignment.class);

            assertThat(decoded).isEqualTo(new TaskAssignment.Role("admin"));
        }

        @Test
        @DisplayName("a hand-written legacy {\"queueName\":\"...\"} literal deserializes to TaskAssignment.Queue")
        void legacyQueueLiteralDeserializes() {
            TaskAssignment decoded = Json.decodeValue("{\"queueName\":\"inbox\"}", TaskAssignment.class);

            assertThat(decoded).isEqualTo(new TaskAssignment.Queue("inbox"));
        }
    }
}
