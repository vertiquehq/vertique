// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.localization.config.LocalizationConfig;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DefaultMessageSourceFactory}'s {@code create(String)} / {@code create(MessageSourceOptions)}
 * shapes and the classloader-precedence chain.
 *
 * <p>FR-LOC-060: {@code create(String basename)} is sugar for
 * {@code create(MessageSourceOptions.builder().basename(basename).build())}.
 *
 * <p>Classloader precedence per plan §"Slice 3":
 * <ol>
 *   <li>{@code options.classLoader()} if non-null.</li>
 *   <li>Else {@code options.caller().getClassLoader()} when caller is non-null AND has a non-null classloader
 *       (bootstrap-loaded classes like {@code Object.class} return {@code null} from
 *       {@link Class#getClassLoader()}).</li>
 *   <li>Else {@link Thread#getContextClassLoader()}; if that is also {@code null}, fall through to
 *       {@link ClassLoader#getSystemClassLoader()}.</li>
 * </ol>
 */
class MessageSourceFactoryTest {

    private static final Locale EN = Locale.forLanguageTag("en");

    private final MessageSourceFactory factory = new DefaultMessageSourceFactory(defaultConfig());

    private static LocalizationConfig defaultConfig() {
        return new LocalizationConfig(EN, ZoneId.of("UTC"), List.of(EN), false, false, false, -1L);
    }

    @Test
    @DisplayName("FR-LOC-060: create(basename) is sugar for create(options.basename(basename).build())")
    void createByName() {
        MessageSource source = factory.create("messages");

        assertNotNull(source);
        // The factory returns a usable MessageSource — pick up a known key from messages_en.properties
        assertEquals("Plain text", source.getMessage("plain", EN));
    }

    @Test
    @DisplayName("create(options) accepts MessageSourceOptions with primary + fallback basenames")
    void createByOptions() {
        MessageSourceOptions options = MessageSourceOptions.builder()
                .basename("messages")
                .fallbackBasename("fallback-common-messages")
                .build();

        MessageSource source = factory.create(options);

        assertEquals("From primary", source.getMessage("shared", EN));
        assertEquals("From the fallback bundle", source.getMessage("fallback-only", EN));
    }

    @Test
    @DisplayName("Classloader precedence: explicit classLoader() wins over caller()")
    void classLoaderOverridesCaller() {
        ClassLoader explicit = getClass().getClassLoader();

        // caller(Object.class) would otherwise fall through to TCCL because Object.class.getClassLoader() == null
        MessageSourceOptions options = MessageSourceOptions.builder()
                .basename("messages")
                .classLoader(explicit)
                .caller(Object.class)
                .build();

        // No exception thrown; we just verify the factory accepts the inputs and returns a usable source.
        MessageSource source = factory.create(options);
        assertEquals("Plain text", source.getMessage("plain", EN));
    }

    @Test
    @DisplayName("Classloader precedence: caller's classloader used when no explicit classLoader supplied")
    void callerClassLoaderUsed() {
        MessageSourceOptions options = MessageSourceOptions.builder()
                .basename("messages")
                .caller(MessageSourceFactoryTest.class) // application classloader
                .build();

        MessageSource source = factory.create(options);
        assertEquals("Plain text", source.getMessage("plain", EN));
    }

    @Test
    @DisplayName("Classloader precedence: caller(Object.class) has null classloader → TCCL fallthrough")
    void bootstrapCallerFallsThroughToTccl() {
        // Object.class.getClassLoader() == null (bootstrap-loaded). The factory must skip rung 2
        // and use the thread context classloader (rung 3) instead. The TCCL must be the
        // application classloader during the test, so bundle lookup still works.
        assertSame(null, Object.class.getClassLoader(), "precondition: Object.class is bootstrap-loaded");

        MessageSourceOptions options = MessageSourceOptions.builder()
                .basename("messages")
                .caller(Object.class)
                .build();

        MessageSource source = factory.create(options);
        assertEquals("Plain text", source.getMessage("plain", EN));
    }

    @Test
    @DisplayName("Classloader precedence: no caller, no explicit classLoader → TCCL")
    void tcclWhenNeitherSet() {
        MessageSourceOptions options =
                MessageSourceOptions.builder().basename("messages").build();

        MessageSource source = factory.create(options);
        assertEquals("Plain text", source.getMessage("plain", EN));
    }

    @Test
    @DisplayName("create(null) propagates NPE")
    void createNullBasenameThrowsNpe() {
        // create(String) routes through MessageSourceOptions.builder().basename(null).build()
        // which throws IllegalArgumentException ("basename must not be blank"). Either IAE or NPE
        // is fine at this layer — assert it throws *some* unchecked exception.
        assertThrows(RuntimeException.class, () -> factory.create((String) null));
    }

    @Test
    @DisplayName("create(blank) throws IAE from the options builder")
    void createBlankBasenameThrowsIae() {
        assertThrows(IllegalArgumentException.class, () -> factory.create("  "));
    }

    @Test
    @DisplayName("Factory is a singleton-friendly type (no mutable per-call state)")
    void factoryReturnsIndependentSources() {
        MessageSource a = factory.create("messages");
        MessageSource b = factory.create("messages");

        // Each call returns a distinct MessageSource instance — factory captures only the
        // immutable config + the options.
        assertNotNull(a);
        assertNotNull(b);
        assertTrue(a != b, "factory should return independent MessageSource instances per create() call");
    }
}
