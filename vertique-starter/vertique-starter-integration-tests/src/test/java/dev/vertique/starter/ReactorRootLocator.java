// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.starter;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the outer reactor root for the starter-family contract tests. */
final class ReactorRootLocator {

    private ReactorRootLocator() {}

    /**
     * Walks up from this module's base directory to the outer reactor root — the first ancestor
     * holding both the Maven wrapper and a POM.
     *
     * @return the reactor root directory
     */
    static Path locate() {
        Path candidate = Path.of(System.getProperty("basedir", System.getProperty("user.dir")))
                .toAbsolutePath()
                .normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("mvnw")) && Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate the reactor root (no ancestor directory contains mvnw)");
    }
}
