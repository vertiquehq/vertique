// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

/**
 * Hand-written companion whose <em>static initializer</em> always throws, so loading it via
 * {@code Class.forName(name, true, loader)} raises {@link ExceptionInInitializerError} (a
 * {@link LinkageError}) at the load step rather than at construction. Used to verify
 * {@link GeneratedCompanions#instantiate} treats a present-but-unloadable companion as broken
 * (routing to {@code onBroken}) instead of letting the raw {@link LinkageError} escape.
 */
public final class GeneratedCompanionsStaticInitFixture_TestProxy implements GeneratedCompanionsStaticInitFixture {

    static {
        // Condition keeps javac from rejecting the initializer as unable to complete normally,
        // while always throwing at class-initialization time.
        if (Boolean.parseBoolean("true")) {
            throw new RuntimeException("intentionally failing static initializer");
        }
    }

    /** Public no-arg constructor (never reached — static init fails first). */
    public GeneratedCompanionsStaticInitFixture_TestProxy() {}
}
