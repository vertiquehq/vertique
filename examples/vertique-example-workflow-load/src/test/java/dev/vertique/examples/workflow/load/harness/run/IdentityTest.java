// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that the identity of the executable is a hash of every classpath entry, and that a changed jar is refused. */
class IdentityTest {

    @Test
    @DisplayName("the hashes cover the module's jar and each dependency, and change when a byte changes")
    void hashes(@TempDir Path dir) throws IOException {
        Path module = dir.resolve("vertique-example-workflow-load-1.jar");
        Path dependency = dir.resolve("lib-1.jar");
        Files.writeString(module, "module");
        Files.writeString(dependency, "dependency");
        String classpath = module + File.pathSeparator + dependency;

        JsonObject identity = Identity.compute(classpath);

        assertEquals(64, identity.getString("moduleSha256").length());
        assertEquals(2, identity.getJsonArray("entries").size());
        Files.writeString(dependency, "dependency!");
        JsonObject changed = Identity.compute(classpath);
        assertEquals(identity.getString("moduleSha256"), changed.getString("moduleSha256"));
        assertNotEquals(identity.getString("dependenciesSha256"), changed.getString("dependenciesSha256"));
        assertNotEquals(identity.getString("classpathSha256"), changed.getString("classpathSha256"));
    }

    @Test
    @DisplayName("a classpath that differs from the recorded identity is refused with the hashes")
    void mismatch(@TempDir Path dir) throws IOException {
        Path module = dir.resolve("vertique-example-workflow-load-1.jar");
        Files.writeString(module, "module");
        Path recorded = dir.resolve("identity.json");
        Files.writeString(recorded, Identity.compute(module.toString()).encodePrettily());

        assertEquals("", Identity.mismatch(module.toString(), recorded));
        Files.writeString(module, "module, rebuilt");
        String message = Identity.mismatch(module.toString(), recorded);

        assertTrue(message.contains("the classpath hashes"), message);
    }

    @Test
    @DisplayName("a directory entry is hashed by its files")
    void directoryEntry(@TempDir Path dir) throws IOException {
        Path classes = Files.createDirectories(dir.resolve("classes"));
        Files.writeString(classes.resolve("A.class"), "a");

        String before = Identity.compute(classes.toString()).getString("classpathSha256");
        Files.writeString(classes.resolve("A.class"), "b");

        assertNotEquals(before, Identity.compute(classes.toString()).getString("classpathSha256"));
    }
}
