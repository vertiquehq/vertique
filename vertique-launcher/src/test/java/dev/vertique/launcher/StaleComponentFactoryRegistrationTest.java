// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.application.VertiqueComponentFactory;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup discovery when a classpath still carries the component-factory registration under its
 * former {@code dev.vertique.core} name. Each test discovers through a classloader that serves
 * {@code META-INF/services} files only from a temporary directory, isolating it from the test
 * classpath's own registration.
 */
class StaleComponentFactoryRegistrationTest {

    private static final String OLD_FILE = "dev.vertique.core.VertiqueComponentFactory";
    private static final String NEW_FILE = "dev.vertique.application.VertiqueComponentFactory";

    @TempDir
    Path services;

    @Test
    @DisplayName("discover: only the old service file fails startup naming the old resource and the rename")
    void discover_onlyOldFile_failsNamingOldResource() throws IOException {
        Path oldFile = write(OLD_FILE, ValidProvider.class.getName());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> discoverWithFixture());

        String message = ex.getMessage();
        assertTrue(message.contains("META-INF/services/" + OLD_FILE), "message names the old resource: " + message);
        assertTrue(
                message.contains(oldFile.toUri().toURL().toString()), "message includes the resource URL: " + message);
        assertTrue(
                message.contains("META-INF/services/" + NEW_FILE),
                "message names the new resource to rename to: " + message);
    }

    @Test
    @DisplayName("discover: old and new files with a valid provider select the factory normally")
    void discover_bothFiles_selectsProviderFromNewFile() throws IOException {
        write(OLD_FILE, ValidProvider.class.getName());
        write(NEW_FILE, ValidProvider.class.getName());

        assertNotNull(discoverWithFixture(), "the provider registered under the new name is selected");
    }

    private VertiqueComponentFactory<VertiqueApplicationComponent> discoverWithFixture() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(new FixtureClassLoader(getClass().getClassLoader(), services));
        try {
            return VertiqueComponentFactoryLoader.discover();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private Path write(String fileName, String provider) throws IOException {
        return Files.writeString(services.resolve(fileName), provider + System.lineSeparator());
    }

    /** Delegates classes to the parent but serves {@code META-INF/services/*} only from the fixture directory. */
    private static final class FixtureClassLoader extends ClassLoader {
        private static final String SERVICES_PREFIX = "META-INF/services/";
        private final Path dir;

        FixtureClassLoader(ClassLoader parent, Path dir) {
            super(parent);
            this.dir = dir;
        }

        @Override
        public URL getResource(String name) {
            if (!name.startsWith(SERVICES_PREFIX)) {
                return super.getResource(name);
            }
            Path file = dir.resolve(name.substring(SERVICES_PREFIX.length()));
            try {
                return Files.exists(file) ? file.toUri().toURL() : null;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (!name.startsWith(SERVICES_PREFIX)) {
                return super.getResources(name);
            }
            URL url = getResource(name);
            return url == null
                    ? Collections.emptyEnumeration()
                    : Collections.enumeration(Collections.singletonList(url));
        }
    }

    /** A public no-argument provider so {@link java.util.ServiceLoader} can instantiate it. */
    public static final class ValidProvider implements VertiqueComponentFactory<VertiqueApplicationComponent> {
        @Override
        public VertiqueApplicationComponent build(dev.vertique.application.VertiqueRuntime runtime) {
            throw new UnsupportedOperationException("not built in this test");
        }
    }
}
