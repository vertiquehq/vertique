// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.aop.Invocation;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ParameterMetadata;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import dev.vertique.core.codegen.ReflectiveParameterMetadata;
import dev.vertique.ratelimit.RateLimitAlgorithmType;
import dev.vertique.ratelimit.RateLimitDecision;
import dev.vertique.ratelimit.RateLimitKey;
import dev.vertique.ratelimit.RateLimitMode;
import dev.vertique.ratelimit.RateLimitOutcome;
import dev.vertique.ratelimit.RateLimiter;
import dev.vertique.ratelimit.exception.RateLimitExceededException;
import dev.vertique.ratelimit.spi.AnonymousRateLimitPolicy;
import dev.vertique.ratelimit.spi.RateLimitAdapterSupport;
import dev.vertique.ratelimit.spi.RateLimitSubject;
import io.vertx.core.Future;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * TP-001: {@link RateLimitedAspect} resolves its target {@link RateLimiter} handle exactly once,
 * at interceptor-build time (fails application startup on an unknown policy name rather than at
 * the first request), and at invocation time resolves the ordered {@code key()} selector paths
 * against the live arguments, derives the key through {@link RateLimitAdapterSupport#subjectKey},
 * bypasses admission entirely on an empty resolution, and otherwise guards the invocation through
 * {@link RateLimiter#execute} (contracts/rate-limit-aop.md).
 */
class RateLimitedAspectTest {

    private static final String KNOWN_POLICY = "search-quota";
    private static final String UNKNOWN_POLICY = "unknown-policy";

    static Stream<MatrixRow> t007ContractMatrix() {
        return Stream.of(
                new MatrixRow(
                        "shouldResolveTheHandleOnceAtConstructionTime",
                        RateLimitedAspectTest::shouldResolveTheHandleOnceAtConstructionTime),
                new MatrixRow(
                        "shouldFailAtConstructionTimeForAnUnknownPolicyName",
                        RateLimitedAspectTest::shouldFailAtConstructionTimeForAnUnknownPolicyName),
                new MatrixRow(
                        "shouldResolveOrderedSelectorPathsAgainstTheActualArguments",
                        RateLimitedAspectTest::shouldResolveOrderedSelectorPathsAgainstTheActualArguments),
                new MatrixRow(
                        "shouldBypassAdmissionWithNoAcquireWhenSubjectKeyIsEmpty",
                        RateLimitedAspectTest::shouldBypassAdmissionWithNoAcquireWhenSubjectKeyIsEmpty),
                new MatrixRow(
                        "shouldGuardTheInvocationThroughExecuteOnPermittedAndOnQuotaExceeded",
                        RateLimitedAspectTest::shouldGuardTheInvocationThroughExecuteOnPermittedAndOnQuotaExceeded));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t007ContractMatrix")
    @DisplayName("enforces the T007 contract matrix")
    void shouldEnforceT007ContractMatrix(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    // --- Row 1: handle resolved once, at interceptor-build time, not per invocation ---

    private static void shouldResolveTheHandleOnceAtConstructionTime() throws NoSuchMethodException {
        RateLimitAdapterSupport adapterSupport = mock(RateLimitAdapterSupport.class);
        RateLimiter limiter = mock(RateLimiter.class);
        when(adapterSupport.limiter(KNOWN_POLICY)).thenReturn(limiter);
        when(adapterSupport.subjectKey(any(), any(), any())).thenReturn(Optional.empty());
        RateLimited annotation = valueAnnotation();
        RateLimitedAspect aspect = new RateLimitedAspect(adapterSupport);

        MethodInterceptor interceptor = aspect.interceptor(valueMetadata(), annotation);

        verify(adapterSupport, times(1)).limiter(KNOWN_POLICY);

        AtomicInteger targetCalls = new AtomicInteger();
        interceptor
                .intercept(invocation(targetCalls, "alice", "eu"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        interceptor
                .intercept(invocation(targetCalls, "alice", "eu"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        verify(adapterSupport, times(1))
                .limiter(KNOWN_POLICY); // still exactly once after two invocations, never per-invocation
    }

    // --- Row 2: unknown policy fails at interceptor-build time, before any invocation ---

    private static void shouldFailAtConstructionTimeForAnUnknownPolicyName() throws NoSuchMethodException {
        RateLimitAdapterSupport adapterSupport = mock(RateLimitAdapterSupport.class);
        when(adapterSupport.limiter(UNKNOWN_POLICY))
                .thenThrow(new IllegalArgumentException("Unknown rate-limit policy: " + UNKNOWN_POLICY));
        RateLimited annotation = unknownPolicyAnnotation();
        RateLimitedAspect aspect = new RateLimitedAspect(adapterSupport);

        assertThatThrownBy(() -> aspect.interceptor(unknownPolicyMetadata(), annotation))
                .as("an unknown policy() name must fail before the interceptor is ever returned")
                .isInstanceOf(IllegalArgumentException.class);

        verify(adapterSupport, times(1)).limiter(UNKNOWN_POLICY);
        verify(adapterSupport, never()).subjectKey(any(), any(), any());
    }

    // --- Row 3: resolved key components are the live arguments, not the literal path strings ---

    private static void shouldResolveOrderedSelectorPathsAgainstTheActualArguments() throws NoSuchMethodException {
        RateLimitAdapterSupport adapterSupport = mock(RateLimitAdapterSupport.class);
        when(adapterSupport.limiter(KNOWN_POLICY)).thenReturn(mock(RateLimiter.class));
        when(adapterSupport.subjectKey(any(), any(), any())).thenReturn(Optional.empty());
        RateLimitedAspect aspect = new RateLimitedAspect(adapterSupport);
        MethodInterceptor interceptor = aspect.interceptor(valueMetadata(), valueAnnotation());
        AtomicInteger targetCalls = new AtomicInteger();

        interceptor
                .intercept(invocation(targetCalls, "alice", "eu"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> extraComponents = ArgumentCaptor.forClass(List.class);
        verify(adapterSupport)
                .subjectKey(
                        eq(RateLimitSubject.EFFECTIVE_PRINCIPAL),
                        eq(AnonymousRateLimitPolicy.SHARED_BUCKET),
                        extraComponents.capture());
        assertThat(extraComponents.getValue())
                .as("key = {\"0\", \"region\"} must resolve to the live (\"alice\", \"eu\") arguments, not the"
                        + " literal path strings")
                .containsExactly("alice", "eu");
    }

    // --- Row 4: an empty subjectKey resolution bypasses admission with zero acquire ---

    private static void shouldBypassAdmissionWithNoAcquireWhenSubjectKeyIsEmpty() throws NoSuchMethodException {
        RateLimitAdapterSupport adapterSupport = mock(RateLimitAdapterSupport.class);
        RateLimiter limiter = mock(RateLimiter.class);
        when(adapterSupport.limiter(KNOWN_POLICY)).thenReturn(limiter);
        when(adapterSupport.subjectKey(any(), any(), any())).thenReturn(Optional.empty());
        RateLimitedAspect aspect = new RateLimitedAspect(adapterSupport);
        MethodInterceptor interceptor = aspect.interceptor(valueMetadata(), valueAnnotation());
        AtomicInteger targetCalls = new AtomicInteger();

        Object result = interceptor
                .intercept(invocation(targetCalls, "alice", "eu"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertThat(result).isEqualTo("value-alice");
        assertThat(targetCalls).as("proceed() must run directly, exactly once").hasValue(1);
        verifyNoInteractions(limiter);
    }

    // --- Row 5: a present subjectKey guards the invocation through execute(...) ---

    private static void shouldGuardTheInvocationThroughExecuteOnPermittedAndOnQuotaExceeded()
            throws NoSuchMethodException {
        RateLimitKey key = RateLimitKey.of("alice", "eu");

        // Permitted: the target runs exactly once, through execute(...).
        RateLimitAdapterSupport permittedSupport = mock(RateLimitAdapterSupport.class);
        RateLimiter permittedLimiter = mock(RateLimiter.class);
        when(permittedSupport.limiter(KNOWN_POLICY)).thenReturn(permittedLimiter);
        when(permittedSupport.subjectKey(any(), any(), any())).thenReturn(Optional.of(key));
        when(permittedLimiter.execute(eq(key), anyLong(), any())).thenAnswer(invocation -> {
            Supplier<Future<Object>> action = invocation.getArgument(2);
            return action.get();
        });
        RateLimitedAspect permittedAspect = new RateLimitedAspect(permittedSupport);
        MethodInterceptor permittedInterceptor = permittedAspect.interceptor(valueMetadata(), valueAnnotation());
        AtomicInteger permittedTargetCalls = new AtomicInteger();

        Object permittedResult = permittedInterceptor
                .intercept(invocation(permittedTargetCalls, "alice", "eu"))
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        assertThat(permittedResult).isEqualTo("value-alice");
        assertThat(permittedTargetCalls)
                .as("a permitted decision must run the target exactly once")
                .hasValue(1);
        verify(permittedLimiter, times(1)).execute(eq(key), eq(1L), any());

        // Quota exceeded: the target is never invoked and the failure propagates unchanged.
        RateLimitAdapterSupport deniedSupport = mock(RateLimitAdapterSupport.class);
        RateLimiter deniedLimiter = mock(RateLimiter.class);
        when(deniedSupport.limiter(KNOWN_POLICY)).thenReturn(deniedLimiter);
        when(deniedSupport.subjectKey(any(), any(), any())).thenReturn(Optional.of(key));
        when(deniedLimiter.execute(eq(key), anyLong(), any()))
                .thenReturn(Future.failedFuture(new RateLimitExceededException(quotaExceededDecision())));
        RateLimitedAspect deniedAspect = new RateLimitedAspect(deniedSupport);
        MethodInterceptor deniedInterceptor = deniedAspect.interceptor(valueMetadata(), valueAnnotation());
        AtomicInteger deniedTargetCalls = new AtomicInteger();

        assertThatThrownBy(() -> {
                    Future<Object> outcome = deniedInterceptor.intercept(invocation(deniedTargetCalls, "alice", "eu"));
                    outcome.toCompletionStage().toCompletableFuture().join();
                })
                .as("QUOTA_EXCEEDED must propagate RateLimitExceededException as a failed Future")
                .hasCauseInstanceOf(RateLimitExceededException.class);
        assertThat(deniedTargetCalls)
                .as("a quota-exceeded decision must never invoke the target")
                .hasValue(0);
    }

    // --- Shared fixtures ---

    private static RateLimited valueAnnotation() throws NoSuchMethodException {
        return valueMethod().getAnnotation(RateLimited.class);
    }

    private static RateLimited unknownPolicyAnnotation() throws NoSuchMethodException {
        return unknownPolicyMethod().getAnnotation(RateLimited.class);
    }

    private static ReflectiveMethodMetadata valueMetadata() throws NoSuchMethodException {
        return new ReflectiveMethodMetadata(
                valueMethod(), List.of(parameterMetadata(0, "id"), parameterMetadata(1, "region")));
    }

    private static ReflectiveMethodMetadata unknownPolicyMetadata() throws NoSuchMethodException {
        return new ReflectiveMethodMetadata(unknownPolicyMethod(), List.of());
    }

    private static Method valueMethod() throws NoSuchMethodException {
        return Target.class.getDeclaredMethod("value", String.class, String.class);
    }

    private static Method unknownPolicyMethod() throws NoSuchMethodException {
        return Target.class.getDeclaredMethod("unknownPolicy");
    }

    private static ParameterMetadata parameterMetadata(int index, String name) {
        return new ReflectiveParameterMetadata(index, name, String.class, String.class, null);
    }

    private static RateLimitDecision quotaExceededDecision() {
        return new RateLimitDecision(
                KNOWN_POLICY,
                RateLimitOutcome.QUOTA_EXCEEDED,
                RateLimitMode.LOCAL,
                RateLimitAlgorithmType.TOKEN_BUCKET,
                10L,
                OptionalLong.of(0L),
                Optional.of(Duration.ofSeconds(1)),
                Optional.empty(),
                Optional.empty());
    }

    private static Invocation invocation(AtomicInteger targetCalls, String id, String region) {
        Object[] arguments = new Object[] {id, region};
        return new Invocation() {
            @Override
            public MethodMetadata target() {
                return null;
            }

            @Override
            public Object[] arguments() {
                return arguments;
            }

            @Override
            public Object instance() {
                return new Target();
            }

            @Override
            public Future<Object> proceed() {
                targetCalls.incrementAndGet();
                return Future.succeededFuture("value-" + id);
            }
        };
    }

    /** Two-argument fixture method: {@code key = {"0", "region"}} resolves to (id, region). */
    static final class Target {
        @RateLimited(
                policy = KNOWN_POLICY,
                key = {"0", "region"})
        String value(String id, String region) {
            return "unused";
        }

        @RateLimited(policy = UNKNOWN_POLICY)
        String unknownPolicy() {
            return "unused";
        }
    }

    /** One named matrix row: an identifier plus its self-contained decisive proof. */
    private record MatrixRow(String name, Executable proof) {
        @Override
        public String toString() {
            return name;
        }
    }
}
