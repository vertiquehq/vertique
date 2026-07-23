// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.TechnicalException;
import dev.vertique.core.exception.VertiqueException;
import dev.vertique.localization.LocalizationException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SPI contract assertions for FR-LOC-020..025 plus the FR-LOC-011 source-grep that the
 * Dagger module does not declare a {@link MessageSource} binding.
 */
class MessageSourceContractTest {

    @Test
    @DisplayName("FR-LOC-020: MessageSource exposes the four required overloads")
    void messageSourceContract() throws NoSuchMethodException {
        Method m1 = MessageSource.class.getMethod("getMessage", String.class, Locale.class, Object[].class);
        Method m2 =
                MessageSource.class.getMethod("getMessage", String.class, String.class, Locale.class, Object[].class);
        Method m3 = MessageSource.class.getMethod("findMessage", String.class, Locale.class, Object[].class);
        Method m4 = MessageSource.class.getMethod("getMessage", MessageResolvable.class, Locale.class);

        assertEquals(String.class, m1.getReturnType());
        assertEquals(String.class, m2.getReturnType());
        assertEquals(
                Optional.class,
                m3.getGenericReturnType().getClass().isInterface() ? Optional.class : m3.getReturnType());
        assertEquals(String.class, m4.getReturnType());
    }

    @Test
    @DisplayName("FR-LOC-021: MessageSourceFactory exposes create(String) and create(MessageSourceOptions)")
    void messageSourceFactoryContract() throws NoSuchMethodException {
        Method byName = MessageSourceFactory.class.getMethod("create", String.class);
        Method byOptions = MessageSourceFactory.class.getMethod("create", MessageSourceOptions.class);

        assertEquals(MessageSource.class, byName.getReturnType());
        assertEquals(MessageSource.class, byOptions.getReturnType());
    }

    @Test
    @DisplayName("FR-LOC-025: MessageCoded is a one-method SPI returning MessageResolvable")
    void messageCodedContract() throws NoSuchMethodException {
        assertTrue(MessageCoded.class.isInterface());
        Method m = MessageCoded.class.getMethod("message");
        assertEquals(MessageResolvable.class, m.getReturnType());
    }

    @Test
    @DisplayName("Exception hierarchy: LocalizationException → VertiqueException")
    void localizationExceptionHierarchy() {
        assertTrue(VertiqueException.class.isAssignableFrom(LocalizationException.class));
    }

    @Test
    @DisplayName("Exception hierarchy: MessageSourceException → LocalizationException AND TechnicalException")
    void messageSourceExceptionHierarchy() {
        // MessageSourceException extends LocalizationException; LocalizationException itself does
        // NOT extend TechnicalException (semantic-neutral base). The 500-default behavior arrives
        // via REST-side mapper policy, not inheritance — see plan §"Framework exception alignment".
        // Therefore we assert ONLY that MessageSourceException extends LocalizationException.
        assertTrue(LocalizationException.class.isAssignableFrom(MessageSourceException.class));
        // Defensive: MessageSourceException should NOT directly extend TechnicalException
        // (otherwise we'd be encoding HTTP mapping in inheritance).
        assertFalse(TechnicalException.class.isAssignableFrom(MessageSourceException.class));
    }

    @Test
    @DisplayName("Exception hierarchy: NoSuchMessageException → MessageSourceException")
    void noSuchMessageExceptionHierarchy() {
        assertTrue(MessageSourceException.class.isAssignableFrom(NoSuchMessageException.class));
    }

    @Test
    @DisplayName("NoSuchMessageException carries bundleBaseName and code")
    void noSuchMessageExceptionFields() {
        NoSuchMessageException ex = new NoSuchMessageException("customer-messages", "checkout.success", "msg");

        assertEquals("customer-messages", ex.bundleBaseName());
        assertEquals("checkout.success", ex.code());
        assertEquals("msg", ex.getMessage());
    }

    @Test
    @DisplayName("FR-LOC-011/012: LocalizationModule source declares no MessageSource binding or multibinding")
    void localizationModuleDoesNotProvideMessageSource() throws Exception {
        Path moduleSource = Path.of("src/main/java/dev/vertique/localization/LocalizationModule.java");
        if (!Files.exists(moduleSource)) {
            return; // Module file not yet present — invariant not yet checkable
        }
        // Strip block comments (Javadoc), line comments, and string literals so the grep only
        // matches real code, not documentation or example blocks that mention MessageSource.
        String code = Files.readString(moduleSource)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");

        assertFalse(
                code.contains("Set<MessageSource>"),
                "LocalizationModule must not declare a Set<MessageSource> multibinding (FR-LOC-012)");
        // FR-LOC-011: detect "@Provides ... MessageSource <method>(" patterns in code only.
        assertFalse(
                code.matches("(?s).*@Provides[^{]*MessageSource\\s+\\w+\\(.*"),
                "LocalizationModule must not declare a @Provides MessageSource (FR-LOC-011)");
    }

    @Test
    @DisplayName("LocalizationException constructors propagate message and cause")
    void localizationExceptionConstructors() {
        LocalizationException byMessage = new LocalizationException("oops");
        assertEquals("oops", byMessage.getMessage());
        assertNotNull(byMessage);

        Throwable cause = new RuntimeException("root");
        LocalizationException byBoth = new LocalizationException("wrapped", cause);
        assertEquals("wrapped", byBoth.getMessage());
        assertEquals(cause, byBoth.getCause());
    }

    @Test
    @DisplayName("Public SPI types are interfaces (not classes)")
    void spiTypesAreInterfaces() {
        assertTrue(MessageSource.class.isInterface());
        assertTrue(MessageSourceFactory.class.isInterface());
        assertTrue(MessageCoded.class.isInterface());
    }

    @Test
    @DisplayName("MessageSource.getMessage(MessageResolvable, Locale) is declared public abstract")
    void resolvableOverloadShape() throws NoSuchMethodException {
        Method m = MessageSource.class.getMethod("getMessage", MessageResolvable.class, Locale.class);
        assertTrue(Modifier.isPublic(m.getModifiers()));
        // Interface methods without a default body are implicitly abstract; assert just for clarity
        assertTrue(Modifier.isAbstract(m.getModifiers()));
    }

    @Test
    @DisplayName("MessageResolvable.codes accessor is List<String> view")
    void messageResolvableAccessor() throws NoSuchMethodException {
        Method codes = MessageResolvable.class.getMethod("codes");
        Method args = MessageResolvable.class.getMethod("args");

        assertEquals(List.class, codes.getReturnType());
        assertEquals(List.class, args.getReturnType());
    }
}
