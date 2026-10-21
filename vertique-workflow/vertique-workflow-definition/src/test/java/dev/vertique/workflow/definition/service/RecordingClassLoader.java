// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * A class loader that delegates to its parent and records every class name it is asked for, so a
 * test can prove that a document-supplied name never reaches the class loader.
 *
 * <p>Install it as the thread context class loader around the call under test with
 * {@link #callWithContextLoader(Supplier)}. A fresh instance is needed per test because the JVM does
 * not ask a loader again for a class it has already initiated.
 */
final class RecordingClassLoader extends ClassLoader {

    private final List<String> requested = new CopyOnWriteArrayList<>();

    RecordingClassLoader() {
        super(RecordingClassLoader.class.getClassLoader());
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        requested.add(name);
        return super.loadClass(name);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        requested.add(name);
        return super.loadClass(name, resolve);
    }

    /** The names this loader was asked for, in request order. */
    List<String> requested() {
        return List.copyOf(requested);
    }

    /** Runs the action with this loader as the thread context class loader and restores the previous one. */
    <T> T callWithContextLoader(Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(this);
        try {
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
