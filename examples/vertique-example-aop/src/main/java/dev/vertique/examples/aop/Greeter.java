// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.aop;

import dev.vertique.micrometer.Timed;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * A simple {@code @Timed}-annotated bean used to prove end-to-end AOP proxy substitution and
 * Micrometer timer recording.
 *
 * <p>Each public method below carries a distinct {@link Timed @Timed} annotation, so the
 * {@code vertique-codegen-aop} processor generates a {@code Greeter$AopProxy} subclass whose
 * overrides wrap the {@code super} call in the {@link dev.vertique.micrometer.TimedAspect} timing
 * interceptor. The two methods carry different {@code @Timed} {@code value}/{@code extraTags},
 * exercising the per-occurrence literal materialization (Bug F2): each occurrence's own attribute
 * values must reach the generated proxy, not the first occurrence's.
 *
 * <p>The bean itself is unaware of metrics — it returns its value unchanged; the proxy observes the
 * call without modifying its outcome.
 */
@Singleton
public class Greeter {

    /**
     * Creates the greeter. The {@code @Inject} constructor is replicated verbatim by the generated
     * {@code Greeter$AopProxy}, which appends an {@code AspectProvider<Timed>} parameter.
     */
    @Inject
    public Greeter() {}

    /**
     * Returns a greeting for the given name. Intercepted by {@code @Timed("vertique.example.greet")};
     * the proxy records a success/error-tagged {@code Timer} named {@code vertique.example.greet} on
     * each call.
     *
     * @param name the name to greet; passed through unchanged into the result
     * @return a future completing with {@code "Hello, <name>!"}
     */
    @Timed("vertique.example.greet")
    public Future<String> greet(String name) {
        return Future.succeededFuture("Hello, " + name + "!");
    }

    /**
     * Returns a farewell for the given name. Intercepted by a distinct
     * {@code @Timed("vertique.example.farewell", extraTags={"tone","formal"})} occurrence — its
     * separate timer name and extra tags prove per-occurrence literal materialization (Bug F2).
     *
     * @param name the name to bid farewell; passed through unchanged into the result
     * @return a future completing with {@code "Goodbye, <name>."}
     */
    @Timed(
            value = "vertique.example.farewell",
            extraTags = {"tone", "formal"})
    public Future<String> farewell(String name) {
        return Future.succeededFuture("Goodbye, " + name + ".");
    }

    /**
     * Returns a future that always fails, used to prove the {@code outcome=ERROR} timer tag is
     * recorded on a failing completion. Intercepted by {@code @Timed("vertique.example.boom")}.
     *
     * @param name the name (unused beyond the failure message)
     * @return a future that always fails with an {@link IllegalStateException}
     */
    @Timed("vertique.example.boom")
    public Future<String> boom(String name) {
        return Future.failedFuture(new IllegalStateException("boom for " + name));
    }

    /**
     * Returns a greeting <em>synchronously</em> — a plain {@link String}, not a {@link Future}. This
     * exercises the proxy's sync-return path: the generated override runs the {@code @Timed} around
     * chain, asserts it settled synchronously (the built-in {@code @Timed} never defers, so the
     * FR-013-05 guard is not tripped), then unwraps the completed chain's value. The
     * {@code @Timed("vertique.example.greet.sync")} occurrence records a {@code Timer} on each call.
     *
     * @param name the name to greet; passed through unchanged into the result
     * @return {@code "Hello, <name>! (sync)"} — the unwrapped synchronous value
     */
    @Timed("vertique.example.greet.sync")
    public String greetSync(String name) {
        return "Hello, " + name + "! (sync)";
    }
}
