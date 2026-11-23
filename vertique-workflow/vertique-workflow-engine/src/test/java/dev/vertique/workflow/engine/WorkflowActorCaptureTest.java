// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.security.SystemIdentities;
import dev.vertique.workflow.actor.DefaultWorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowActorCapture;
import dev.vertique.workflow.actor.WorkflowActorMapper;
import dev.vertique.workflow.actor.WorkflowAttribution;
import dev.vertique.workflow.engine.executor.CommandActors;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the actor that the registry stores for a definition change: the same actor, with the same cleaning and the
 * same attribution, that the engine takes for a command, so that a stored definition change and an instance's history
 * entry name a caller the same way.
 */
class WorkflowActorCaptureTest {

    /** Characters the actor text rule removes, built at run time so that no source line carries them. */
    private static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E);

    private static final String ZERO_WIDTH = Character.toString(0x200B);
    private static final String ZERO_WIDTH_JOINER = Character.toString(0x200D);
    private static final String LINE_SEPARATOR = Character.toString(0x2028);

    /** Holds the values a test binds; reads only. */
    private static final class Held implements ContextHolder {
        private final Map<Class<?>, Object> values = new HashMap<>();

        Held with(Class<?> type, Object value) {
            values.put(type, value);
            return this;
        }

        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.ofNullable(values.get(type)).map(type::cast);
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            throw new UnsupportedOperationException("capturing only reads the context");
        }
    }

    private static SecurityIdentity user(String id) {
        return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of()));
    }

    private static SecurityIdentity serviceActingFor(String service, String subject) {
        return new SecurityIdentity(
                new PrincipalRef(PrincipalType.SERVICE, service, Map.of()),
                Optional.of(new PrincipalRef(PrincipalType.USER, subject, Map.of())),
                Optional.empty(),
                Optional.empty());
    }

    private static Held holding(SecurityIdentity identity) {
        return new Held().with(SecurityContext.class, SecurityContexts.unauthenticated(identity));
    }

    private static Stream<Arguments> holders() {
        return Stream.of(
                Arguments.of("no context", new Held()),
                Arguments.of("anonymous", holding(SecurityIdentity.anonymous())),
                Arguments.of("user", holding(user("u-1"))),
                Arguments.of("service for a user", holding(serviceActingFor("svc-1", "u-7"))),
                Arguments.of("system", holding(SystemIdentities.scheduledJob("nightly"))),
                Arguments.of(
                        "hostile id", holding(user("u\u0000-" + RIGHT_TO_LEFT_OVERRIDE + "1\n" + "x".repeat(400)))),
                Arguments.of("id of format characters only", holding(user(ZERO_WIDTH + ZERO_WIDTH_JOINER))),
                Arguments.of("hostile subject", holding(serviceActingFor("svc-1", "u\r\n7" + LINE_SEPARATOR))),
                Arguments.of(
                        "degraded snapshot",
                        holding(user("u-1"))
                                .with(
                                        SnapshotDegradationMarker.class,
                                        new SnapshotDegradationMarker("TAMPERED", Optional.empty()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("holders")
    @DisplayName("the stored actor is the one the engine takes for a command")
    void theStoredActorIsTheOneTheEngineTakesForACommand(String label, Held holder) {
        WorkflowActorMapper mapper = new DefaultWorkflowActorMapper();

        WorkflowAttribution stored = WorkflowActorCapture.capture(holder, mapper);

        WorkflowAttribution engine = new CommandActors(holder, mapper).capture();
        assertThat(stored.actor()).as(label).isEqualTo(engine.actor());
        assertThat(stored.onBehalfOf()).as(label).isEqualTo(engine.onBehalfOf());
        assertThat(stored.status()).as(label).isEqualTo(engine.status());
    }

    @Test
    @DisplayName("no identity and an anonymous identity are unattributed, and the mapper never sees them")
    void noIdentityIsUnattributed() {
        WorkflowActorMapper refusing = identity -> {
            throw new AssertionError("the mapper must not see an anonymous identity");
        };

        assertThat(WorkflowActorCapture.capture(new Held(), refusing)).isEqualTo(WorkflowAttribution.absent());
        assertThat(WorkflowActorCapture.capture(holding(SecurityIdentity.anonymous()), refusing))
                .isEqualTo(WorkflowAttribution.absent());
        assertThat(WorkflowAttribution.absent().actor()).isNull();
        assertThat(WorkflowAttribution.absent().status()).isEqualTo(WorkflowAttribution.Status.ABSENT);
    }

    @Test
    @DisplayName("an exception the mapper throws propagates unchanged")
    void aMapperFailurePropagates() {
        IllegalArgumentException refusal = new IllegalArgumentException("not mappable");
        WorkflowActorMapper failing = identity -> {
            throw refusal;
        };

        assertThatThrownBy(() -> WorkflowActorCapture.capture(holding(user("u-1")), failing))
                .isSameAs(refusal);
    }

    @Test
    void preservesActorAndSubjectTextAndDropsSubjectAttributes() {
        String id = "u\n-" + "x".repeat(400);
        String subjectId = "s" + RIGHT_TO_LEFT_OVERRIDE + "7";
        WorkflowAttribution attribution = new WorkflowAttribution(
                new WorkflowActor.User(id),
                new PrincipalRef(PrincipalType.USER, subjectId, Map.of("issuer", "request-only")),
                WorkflowAttribution.Status.VERIFIED);
        assertThat(attribution.actor()).isEqualTo(new WorkflowActor.User(id));
        assertThat(attribution.onBehalfOf()).isEqualTo(new PrincipalRef(PrincipalType.USER, subjectId, Map.of()));
    }

    @Test
    @DisplayName("the system actor carries its exact reason and is verified")
    void theSystemActorCarriesItsReason() {
        WorkflowAttribution system = WorkflowAttribution.system("boot\nstrap");

        assertThat(system.actor()).isEqualTo(new WorkflowActor.System("boot\nstrap"));
        assertThat(system.onBehalfOf()).isNull();
        assertThat(system.status()).isEqualTo(WorkflowAttribution.Status.VERIFIED);
    }
}
