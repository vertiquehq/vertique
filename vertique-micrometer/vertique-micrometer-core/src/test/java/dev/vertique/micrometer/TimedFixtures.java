// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.Invocations;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.core.codegen.MethodMetadata;
import dev.vertique.core.codegen.ParameterMetadata;
import io.vertx.core.Future;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

/**
 * Test fixtures for the slice-1.3 {@code @Timed} runtime proof.
 *
 * <p><strong>Runtime-proof approach (direct-instantiation fallback).</strong> Wiring test-scope
 * Dagger plus the {@code vertique-codegen-aop} annotation processor into {@code vertique-micrometer-core}
 * (to make Dagger substitute a generated {@code Bean$AopProxy}) is disproportionate for this slice.
 * Instead, the {@code *$AopProxy} classes below are <em>hand-written to mirror exactly what
 * {@code AopProxyEmitter} generates at compile time</em>: each
 * <ul>
 *   <li>{@code extends} its bean and overrides the intercepted method;
 *   <li>holds a {@code static final MethodMetadata} constant and builds its
 *       {@link MethodInterceptor}{@code []} chain from {@code timedProvider.interceptor(META, LITERAL)}
 *       in an {@code @Inject}-shaped constructor;
 *   <li>runs the chain via {@link Invocations#run} around a direct {@code super.<method>(...)}
 *       terminal call.
 * </ul>
 * This drives the <em>real</em> {@code TimedAspect} over a real {@code SimpleMeterRegistry} through
 * the <em>real</em> {@link Invocations} nester — proving interception and recording without standing
 * up a full APT+Dagger pipeline in this module. The centerpiece test additionally asserts the proxy's
 * {@code getClass().getSimpleName()} ends with {@code $AopProxy}, the property Dagger substitution
 * would establish.
 */
final class TimedFixtures {

    private TimedFixtures() {}

    // --- beans ---

    /** A bean whose {@code @Timed} {@code Future<String>} method returns a greeting. */
    static class GreeterService {

        private final String greeting;

        GreeterService(String greeting) {
            this.greeting = greeting;
        }

        @Timed("myOp")
        Future<String> greet(String name) {
            return Future.succeededFuture(greeting + " " + name);
        }
    }

    /** A bean whose {@code @Timed} method fails its returned {@code Future}. */
    static class FailingFutureService {

        @Timed("myOp")
        Future<String> boom() {
            return Future.failedFuture(new RuntimeException("boom"));
        }
    }

    /** A bean whose {@code @Timed} method throws synchronously (propagates unchanged). */
    static class ThrowingService {

        @Timed("myOp")
        Future<String> npe() {
            throw new NullPointerException("npe");
        }
    }

    /** A bean whose {@code @Timed} carries an empty {@code value()} (name auto-derived per §3b). */
    static class AutoNamedService {

        @Timed
        Future<String> greet(String name) {
            return Future.succeededFuture("hi " + name);
        }
    }

    // --- hand-written proxies mirroring AopProxyEmitter output ---

    /** Proxy over {@link GreeterService#greet(String)}. */
    static final class GreeterService$AopProxy extends GreeterService {

        private static final MethodMetadata GREET_META =
                method("greet", GreeterService.class, String.class, "name", String.class);

        private static final Timed GREET_LITERAL = literal("myOp");

        private final MethodInterceptor[] greet$chain;

        GreeterService$AopProxy(String greeting, AspectProvider<Timed> timedProvider) {
            super(greeting);
            this.greet$chain = new MethodInterceptor[] {timedProvider.interceptor(GREET_META, GREET_LITERAL)};
        }

        @Override
        Future<String> greet(String name) {
            Object[] args = {name};
            Future<Object> result = Invocations.run(this, GREET_META, args, this.greet$chain, () -> super.greet(name)
                    .map(v -> (Object) v));
            return result.map(o -> (String) o);
        }
    }

    /** Proxy over {@link FailingFutureService#boom()}. */
    static final class FailingFutureService$AopProxy extends FailingFutureService {

        private static final MethodMetadata BOOM_META = method("boom", FailingFutureService.class, String.class);
        private static final Timed BOOM_LITERAL = literal("myOp");
        private final MethodInterceptor[] boom$chain;

        FailingFutureService$AopProxy(AspectProvider<Timed> timedProvider) {
            this.boom$chain = new MethodInterceptor[] {timedProvider.interceptor(BOOM_META, BOOM_LITERAL)};
        }

        @Override
        Future<String> boom() {
            Object[] args = {};
            Future<Object> result = Invocations.run(
                    this, BOOM_META, args, this.boom$chain, () -> super.boom().map(v -> (Object) v));
            return result.map(o -> (String) o);
        }
    }

    /** Proxy over {@link ThrowingService#npe()}. */
    static final class ThrowingService$AopProxy extends ThrowingService {

        private static final MethodMetadata NPE_META = method("npe", ThrowingService.class, String.class);
        private static final Timed NPE_LITERAL = literal("myOp");
        private final MethodInterceptor[] npe$chain;

        ThrowingService$AopProxy(AspectProvider<Timed> timedProvider) {
            this.npe$chain = new MethodInterceptor[] {timedProvider.interceptor(NPE_META, NPE_LITERAL)};
        }

        @Override
        Future<String> npe() {
            Object[] args = {};
            Future<Object> result = Invocations.run(
                    this, NPE_META, args, this.npe$chain, () -> super.npe().map(v -> (Object) v));
            return result.map(o -> (String) o);
        }
    }

    /** Proxy over {@link AutoNamedService#greet(String)} ({@code @Timed} with empty {@code value()}). */
    static final class AutoNamedService$AopProxy extends AutoNamedService {

        private static final MethodMetadata GREET_META =
                method("greet", AutoNamedService.class, String.class, "name", String.class);
        private static final Timed GREET_LITERAL = literal("");
        private final MethodInterceptor[] greet$chain;

        AutoNamedService$AopProxy(AspectProvider<Timed> timedProvider) {
            this.greet$chain = new MethodInterceptor[] {timedProvider.interceptor(GREET_META, GREET_LITERAL)};
        }

        @Override
        Future<String> greet(String name) {
            Object[] args = {name};
            Future<Object> result = Invocations.run(this, GREET_META, args, this.greet$chain, () -> super.greet(name)
                    .map(v -> (Object) v));
            return result.map(o -> (String) o);
        }
    }

    // --- helpers ---

    /**
     * Builds a {@code @Timed} literal carrying the given {@code value()} and no extra tags, mirroring
     * the generated {@code Timed$AopLiteral} the real proxy would hold.
     *
     * @param value the {@code @Timed} metric name
     * @return a {@code @Timed} instance with that {@code value()} and empty {@code extraTags()}
     */
    static Timed literal(String value) {
        return literal(value, new String[0]);
    }

    /**
     * Builds a {@code @Timed} literal carrying the given {@code value()} and {@code extraTags()},
     * mirroring the generated {@code Timed$AopLiteral} the real proxy would hold. Used to exercise the
     * {@link TimedAspect#interceptor} even-length validation (Bug F6).
     *
     * @param value the {@code @Timed} metric name
     * @param extraTags the {@code extraTags()} elements (may be odd-length to drive the validation)
     * @return a {@code @Timed} instance with that {@code value()} and {@code extraTags()}
     */
    static Timed literal(String value, String... extraTags) {
        return new Timed() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return Timed.class;
            }

            @Override
            public String value() {
                return value;
            }

            @Override
            public String[] extraTags() {
                return extraTags.clone();
            }
        };
    }

    /**
     * Builds a reflection-free-style {@link MethodMetadata} constant for a zero-or-one-parameter
     * method, mirroring the {@code MethodMetadataImpl} the codegen emitter would generate.
     *
     * @param name the method name
     * @param declaringType the declaring class
     * @param returnType the erased return type
     * @param paramNameThenType optional {@code (paramName, paramType)} pair for a single parameter
     * @return the metadata view
     */
    static MethodMetadata method(
            String name, Class<?> declaringType, Class<?> returnType, Object... paramNameThenType) {
        String paramName = paramNameThenType.length > 0 ? (String) paramNameThenType[0] : null;
        Class<?> paramType = paramNameThenType.length > 1 ? (Class<?>) paramNameThenType[1] : null;
        Class<?>[] paramTypes = paramType != null ? new Class<?>[] {paramType} : new Class<?>[0];
        return new MethodMetadata() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Class<?> declaringType() {
                return declaringType;
            }

            @Override
            public Class<?> returnType() {
                return returnType;
            }

            @Override
            public Class<?>[] parameterTypes() {
                return paramTypes.clone();
            }

            @Override
            public List<ParameterMetadata> parameters() {
                if (paramName == null) {
                    return List.of();
                }
                return List.of(parameter(paramName, paramType));
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
                return returnType;
            }

            @Override
            public Method asMethod() {
                throw new UnsupportedOperationException("not needed for @Timed runtime proof");
            }
        };
    }

    /** Builds a minimal {@link ParameterMetadata} for the single-parameter fixtures. */
    private static ParameterMetadata parameter(String name, Class<?> type) {
        return new ParameterMetadata() {
            @Override
            public int index() {
                return 0;
            }

            @Override
            public String name() {
                return name;
            }

            @Override
            public Class<?> type() {
                return type;
            }

            @Override
            public <A extends Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
                return Optional.empty();
            }

            @Override
            public boolean hasAnnotation(Class<? extends Annotation> annotationType) {
                return false;
            }

            @Override
            public Type genericType() {
                return type;
            }
        };
    }
}
