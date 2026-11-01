// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A class loader that records the name of every class it is asked for before it hands the request to its parent.
 *
 * <p>The application code of a node reads the loader of its thread's context when it resolves a class by name, and a
 * Vert.x context keeps the context class loader of the thread that created it. A test installs the probe around
 * everything it does on a node, {@link #during(Supplier)}, so that the contexts of that node carry it, and then reads
 * which names the node asked for. The parent of the probe is the loader the node is built under.
 */
final class ClassLoadProbe extends ClassLoader {

    private final Set<String> requested = ConcurrentHashMap.newKeySet();

    ClassLoadProbe(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        requested.add(name);
        return super.loadClass(name, resolve);
    }

    /** Runs the action with this probe as the context class loader of the calling thread. */
    <T> T during(Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(this);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(before);
        }
    }

    /** The requested class names that start with the prefix. */
    List<String> requestedWithPrefix(String prefix) {
        return requested.stream()
                .filter(name -> name.startsWith(prefix))
                .sorted()
                .toList();
    }

    /**
     * Whether a Vert.x context created while the probe is installed carries it, which is what lets the probe see the
     * class resolution of the callbacks that run on such a context.
     */
    boolean contextsCreatedDuringCarryTheProbe(Vertx vertx) {
        Context context = during(vertx::getOrCreateContext);
        CompletableFuture<ClassLoader> seen = new CompletableFuture<>();
        context.runOnContext(ignored -> seen.complete(Thread.currentThread().getContextClassLoader()));
        try {
            return seen.get(10, TimeUnit.SECONDS) == this;
        } catch (Exception e) {
            throw new AssertionError("The context did not run the check", e);
        }
    }
}
