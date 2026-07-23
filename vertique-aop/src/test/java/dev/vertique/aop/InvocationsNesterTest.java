// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.aop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ParameterMetadata;
import io.vertx.core.Future;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Behavior tests for the {@link Invocations} continuation nester.
 *
 * <p>These tests are RED in slice 1.1: {@link Invocations#run} is a failing stub, so each test that
 * actually drives the nester throws {@link UnsupportedOperationException}. The green step (slice 1.1
 * implementation) supplies the continuation-passing fold and turns them green.
 */
class InvocationsNesterTest {

    private static final MethodMetadata TARGET = new StubMethodMetadata();

    /**
     * Given an empty interceptor chain, when {@code run} executes, then the terminal supplier is
     * called directly and its result is returned unchanged.
     */
    @Test
    void emptyChain_callsTerminalDirectly() {
        AtomicInteger terminalCalls = new AtomicInteger();
        Object[] args = {"in"};

        Future<Object> result = Invocations.run(this, TARGET, args, new MethodInterceptor[0], () -> {
            terminalCalls.incrementAndGet();
            return Future.succeededFuture("terminal");
        });

        assertTrue(result.succeeded());
        assertEquals("terminal", result.result());
        assertEquals(1, terminalCalls.get());
    }

    /**
     * Given a single interceptor that passes the outcome through, when {@code run} executes, then the
     * terminal is wrapped exactly once and the interceptor sees the result.
     */
    @Test
    void singleInterceptor_wrapsTerminalOnce() {
        AtomicInteger terminalCalls = new AtomicInteger();
        AtomicInteger interceptorCalls = new AtomicInteger();
        Object[] args = {"in"};

        MethodInterceptor passThrough = invocation -> {
            interceptorCalls.incrementAndGet();
            return invocation.proceed();
        };

        Future<Object> result = Invocations.run(this, TARGET, args, new MethodInterceptor[] {passThrough}, () -> {
            terminalCalls.incrementAndGet();
            return Future.succeededFuture("terminal");
        });

        assertTrue(result.succeeded());
        assertEquals("terminal", result.result());
        assertEquals(1, interceptorCalls.get());
        assertEquals(1, terminalCalls.get());
    }

    /**
     * Given a one-element chain whose interceptor calls {@code proceed()} twice and a terminal
     * supplier counting its invocations, when {@code run} executes, then the terminal is invoked
     * twice (continuation captured per call, not a mutated shared index) and the second proceed sees
     * the current {@code arguments()}.
     */
    @Test
    void reentrantProceed_reinvokesChainFromPosition() {
        AtomicInteger terminalCalls = new AtomicInteger();
        Object[] args = {"first"};

        // The interceptor calls proceed() twice. Between the calls it mutates the live argument
        // array, so the second terminal dispatch must observe the updated value — proving the
        // continuation is re-entrant and reads arguments() at each dispatch.
        MethodInterceptor reentrant = invocation -> {
            Future<Object> firstCall = invocation.proceed();
            invocation.arguments()[0] = "second";
            Future<Object> secondCall = invocation.proceed();
            return firstCall.compose(ignored -> secondCall);
        };

        Future<Object> result = Invocations.run(this, TARGET, args, new MethodInterceptor[] {reentrant}, () -> {
            int n = terminalCalls.incrementAndGet();
            // Echo back the argument the terminal observed on this dispatch.
            return Future.succeededFuture("terminal#" + n + ":" + args[0]);
        });

        assertTrue(result.succeeded());
        // Terminal ran twice (re-entrant proceed), and the second dispatch saw the mutated arg.
        assertEquals(2, terminalCalls.get());
        assertEquals("terminal#2:second", result.result());
        // The live array reflects the last mutation.
        assertArrayEquals(new Object[] {"second"}, args);
    }

    /**
     * Given a terminal supplier that throws synchronously, when {@code run} executes (here with an
     * empty chain so the terminal is invoked directly), then the synchronous throw is captured into a
     * failed future rather than propagating — upholding the SPI contract that the chain never throws
     * synchronously and always returns a {@link Future}.
     */
    @Test
    void terminalSyncThrow_becomesFailedFuture() {
        Object[] args = {};
        IllegalStateException boom = new IllegalStateException("sync-throw");

        Future<Object> result = Invocations.run(this, TARGET, args, new MethodInterceptor[0], () -> {
            throw boom;
        });

        assertTrue(result.failed(), "a synchronous throw from the terminal becomes a failed future");
        assertSame(boom, result.cause(), "the original throwable propagates unchanged as the failure cause");
    }

    /**
     * Given a single interceptor wrapping a terminal that throws synchronously, when {@code run}
     * executes, then the sync throw is captured into a failed future the interceptor observes through
     * {@link Invocation#proceed()} — not as a thrown exception.
     */
    @Test
    void terminalSyncThrowThroughInterceptor_becomesFailedFuture() {
        Object[] args = {};
        RuntimeException boom = new RuntimeException("sync-throw-through-chain");

        MethodInterceptor passThrough = invocation -> invocation.proceed();

        Future<Object> result = Invocations.run(this, TARGET, args, new MethodInterceptor[] {passThrough}, () -> {
            throw boom;
        });

        assertTrue(result.failed(), "a synchronous terminal throw surfaces as a failed future via proceed()");
        assertSame(boom, result.cause(), "the original throwable propagates unchanged as the failure cause");
    }

    /**
     * Minimal {@link MethodMetadata} stub for nester tests — the nester does not interpret the
     * metadata, it only passes it to the {@link Invocation}.
     */
    private static final class StubMethodMetadata implements MethodMetadata {
        @Override
        public String name() {
            return "stub";
        }

        @Override
        public Class<?> declaringType() {
            return InvocationsNesterTest.class;
        }

        @Override
        public Class<?> returnType() {
            return Object.class;
        }

        @Override
        public Class<?>[] parameterTypes() {
            return new Class<?>[] {String.class};
        }

        @Override
        public List<ParameterMetadata> parameters() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }

        @Override
        public boolean hasAnnotation(Class<? extends Annotation> type) {
            return false;
        }

        @Override
        public Type genericReturnType() {
            return Object.class;
        }

        @Override
        public Method asMethod() {
            throw new UnsupportedOperationException("not needed for nester tests");
        }
    }
}
