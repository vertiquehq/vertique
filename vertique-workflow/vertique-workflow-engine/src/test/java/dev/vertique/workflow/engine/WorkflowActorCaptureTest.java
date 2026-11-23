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
 * Verifies the actor that the registry stores for a definition change: the same actor, with the same text, that the
 * engine takes for a command, so that a stored definition change and an instance's history entry name a caller the
 * same way. Also verifies which text an attribution accepts and which it refuses.
 */
class WorkflowActorCaptureTest {

    /** Characters that the attribution keeps verbatim, built at run time so that no source line carries them. */
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
                Arguments.of("hostile id", holding(user("u-" + RIGHT_TO_LEFT_OVERRIDE + "1\n" + "x".repeat(200)))),
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
        assertThat(WorkflowAttribution.absent().onBehalfOf()).isNull();
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
        String id = "u\n-" + "x".repeat(200);
        String subjectId = "s" + RIGHT_TO_LEFT_OVERRIDE + "7";
        WorkflowAttribution attribution = new WorkflowAttribution(
                new WorkflowActor.User(id),
                new PrincipalRef(PrincipalType.USER, subjectId, Map.of("issuer", "request-only")));
        assertThat(attribution.actor()).isEqualTo(new WorkflowActor.User(id));
        assertThat(attribution.onBehalfOf()).isEqualTo(new PrincipalRef(PrincipalType.USER, subjectId, Map.of()));
    }

    @Test
    @DisplayName("the system actor carries its exact reason")
    void theSystemActorCarriesItsReason() {
        WorkflowAttribution system = WorkflowAttribution.system("boot\nstrap");

        assertThat(system.actor()).isEqualTo(new WorkflowActor.System("boot\nstrap"));
        assertThat(system.onBehalfOf()).isNull();
    }

    @Test
    @DisplayName("a degraded snapshot is recorded with its actor like any other")
    void aDegradedSnapshotIsRecordedWithItsActor() {
        Held degraded = holding(user("u-1"))
                .with(SnapshotDegradationMarker.class, new SnapshotDegradationMarker("TAMPERED", Optional.empty()));

        WorkflowAttribution attribution = WorkflowActorCapture.capture(degraded, new DefaultWorkflowActorMapper());

        assertThat(attribution.actor()).isEqualTo(new WorkflowActor.User("u-1"));
        assertThat(attribution.onBehalfOf()).isNull();
    }

    @Test
    @DisplayName("a failed capture is recorded as no actor")
    void aFailedCaptureIsRecordedAsNoActor() {
        WorkflowAttribution attribution = WorkflowActorCapture.bestEffort(() -> {
            throw new IllegalStateException("unreadable");
        });

        assertThat(attribution).isEqualTo(WorkflowAttribution.absent());
        assertThat(attribution.actor()).isNull();
    }

    @Test
    @DisplayName("an id of exactly 255 code points is accepted and one of 256 is refused, for every text field")
    void theLengthLimitIsInCodePoints() {
        String limit = "x".repeat(WorkflowAttribution.MAX_CODE_POINTS);
        String emoji = Character.toString(0x1F600);
        String limitInCodePoints = emoji.repeat(WorkflowAttribution.MAX_CODE_POINTS);

        assertThat(new WorkflowAttribution(new WorkflowActor.User(limit), null).actor())
                .isEqualTo(new WorkflowActor.User(limit));
        assertThat(new WorkflowAttribution(new WorkflowActor.Service(limitInCodePoints), null).actor())
                .isEqualTo(new WorkflowActor.Service(limitInCodePoints));
        assertThat(WorkflowAttribution.system(limit).actor()).isEqualTo(new WorkflowActor.System(limit));
        assertThat(attributedFor(limit).onBehalfOf().id()).isEqualTo(limit);
        String over = limit + "x";
        assertThatThrownBy(() -> new WorkflowAttribution(new WorkflowActor.User(over), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowAttribution(new WorkflowActor.Service(over), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowAttribution.system(over)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attributedFor(over)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowAttribution(new WorkflowActor.User(limitInCodePoints + emoji), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("text with a NUL character or an unpaired surrogate is refused, without echoing the text")
    void unstorableTextIsRefused() {
        String nul = "u\u0000-1";
        String lonelyHigh = "u" + '\uD83D' + "-1";
        String lonelyLow = "u" + '\uDE00' + "-1";
        String trailingHigh = "u-1" + '\uD83D';

        assertThatThrownBy(() -> new WorkflowAttribution(new WorkflowActor.User(nul), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("u-1");
        assertThatThrownBy(() -> new WorkflowAttribution(new WorkflowActor.Service(lonelyHigh), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowAttribution.system(lonelyLow)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowAttribution.system(trailingHigh)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attributedFor(nul)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attributedFor(lonelyHigh)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a valid surrogate pair, bidi and control text are kept verbatim")
    void validTextIsKeptVerbatim() {
        String pair = "u-" + Character.toString(0x1F600);
        String hostile = "u\n-" + RIGHT_TO_LEFT_OVERRIDE + ZERO_WIDTH + ZERO_WIDTH_JOINER + LINE_SEPARATOR + "\t ";

        assertThat(new WorkflowAttribution(new WorkflowActor.User(pair), null).actor())
                .isEqualTo(new WorkflowActor.User(pair));
        assertThat(new WorkflowAttribution(new WorkflowActor.User(hostile), null).actor())
                .isEqualTo(new WorkflowActor.User(hostile));
        assertThat(WorkflowAttribution.system(hostile).actor()).isEqualTo(new WorkflowActor.System(hostile));
        assertThat(attributedFor(hostile).onBehalfOf().id()).isEqualTo(hostile);
    }

    private static WorkflowAttribution attributedFor(String subjectId) {
        return new WorkflowAttribution(
                new WorkflowActor.Service("svc"), new PrincipalRef(PrincipalType.USER, subjectId, Map.of()));
    }
}
