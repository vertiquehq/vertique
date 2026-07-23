// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.localization.config.LocalizationConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * §11.2 message-source tests plus FR-LOC-060..089 (formatting, candidate-locale order,
 * findMessage, useCodeAsDefaultMessage, fallback chain, ROOT bundle resolution).
 *
 * <p>JVM time zone is pinned to {@code Europe/Helsinki} for the duration of this class so
 * {@code MessageFormat}'s {@code {n,date,...}} / {@code {n,time,...}} subformats produce the
 * deterministic outputs the PRD §11.2 matrix expects (those subformats render in the JVM
 * default zone). {@code @Execution(SAME_THREAD)} prevents future Surefire parallel switches
 * from racing siblings that also depend on the system zone.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DefaultMessageSourceTest {

    private static final Locale FI = Locale.forLanguageTag("fi");
    private static final Locale SV = Locale.forLanguageTag("sv");
    private static final Locale EN = Locale.forLanguageTag("en");
    private static final Locale LV = Locale.forLanguageTag("lv");
    private static final List<Locale> SUPPORTED = List.of(FI, SV, EN);

    private static TimeZone originalZone;

    @BeforeAll
    static void pinTimezone() {
        originalZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Helsinki"));
    }

    @AfterAll
    static void restoreTimezone() {
        TimeZone.setDefault(originalZone);
    }

    private static MessageSourceFactory factoryWithDefault(
            Locale defaultLocale, boolean systemFallback, boolean codeAsDefault, boolean alwaysFormat) {
        LocalizationConfig config = new LocalizationConfig(
                defaultLocale,
                ZoneId.of("Europe/Helsinki"),
                SUPPORTED,
                systemFallback,
                codeAsDefault,
                alwaysFormat,
                -1L);
        return new DefaultMessageSourceFactory(config);
    }

    private static MessageSource source(Locale defaultLocale, String basename) {
        return factoryWithDefault(defaultLocale, false, false, false).create(basename);
    }

    @Nested
    @DisplayName("§11.2 formatting matrix (JVM zone pinned to Europe/Helsinki)")
    class FormattingMatrix {

        private final BigDecimal amount = new BigDecimal("10.01");
        private final LocalDate date = LocalDate.of(2024, 2, 29);
        private final LocalTime time = LocalTime.of(1, 59, 59);
        private final ZoneId zone = ZoneId.of("Europe/Helsinki");
        private final Date dateArg = Date.from(date.atStartOfDay(zone).toInstant());
        private final Date timeArg = Date.from(time.atDate(date).atZone(zone).toInstant());

        @Test
        @DisplayName("requested fi, default fi → Testi summa 10,01 päivänä 29.02.2024 klo 01:59")
        void fi() {
            MessageSource src = source(FI, "messages");
            assertEquals(
                    "Testi summa 10,01 päivänä 29.02.2024 klo 01:59",
                    src.getMessage("test", FI, amount, dateArg, timeArg));
        }

        @Test
        @DisplayName("requested en, default fi → Test amount 10.01 at date 29/02/2024 and time 01:59")
        void en() {
            MessageSource src = source(FI, "messages");
            assertEquals(
                    "Test amount 10.01 at date 29/02/2024 and time 01:59",
                    src.getMessage("test", EN, amount, dateArg, timeArg));
        }

        @Test
        @DisplayName("requested sv, default fi → Testsumma 10,01 den 29.02.2024 kl 01:59")
        void sv() {
            MessageSource src = source(FI, "messages");
            assertEquals(
                    "Testsumma 10,01 den 29.02.2024 kl 01:59", src.getMessage("test", SV, amount, dateArg, timeArg));
        }

        @Test
        @DisplayName("requested lv (unsupported), default fi → Finnish pattern, lv formatting")
        void lvWithFiDefault() {
            // Pattern comes from messages_fi.properties (fallback locale), but formatting uses
            // requested locale lv (FR-LOC-082). lv uses comma decimal separator like Finnish.
            MessageSource src = source(FI, "messages");
            assertEquals(
                    "Testi summa 10,01 päivänä 29.02.2024 klo 01:59",
                    src.getMessage("test", LV, amount, dateArg, timeArg));
        }

        @Test
        @DisplayName(
                "requested lv, default en, fallbackToSystemLocale=true, JVM English → English pattern, lv formatting")
        void lvWithSystemFallback() {
            // System default is whatever the host has; the test forces JVM English locale temporarily.
            Locale priorDefault = Locale.getDefault();
            try {
                Locale.setDefault(Locale.ENGLISH);
                MessageSource src = factoryWithDefault(EN, true, false, false).create("messages");

                assertEquals(
                        "Test amount 10,01 at date 29/02/2024 and time 01:59",
                        src.getMessage("test", LV, amount, dateArg, timeArg));
            } finally {
                Locale.setDefault(priorDefault);
            }
        }
    }

    @Nested
    @DisplayName("FR-LOC-080: raw pattern (no args, alwaysUseMessageFormat=false)")
    class RawPatternNoArgs {

        @Test
        @DisplayName("requested fi → raw Finnish pattern returned unchanged")
        void fi() {
            MessageSource src = source(FI, "messages");
            assertEquals(
                    "Testi summa {0,number,#,###.00} päivänä {1,date,dd.MM.yyyy} klo {2,time,HH:mm}",
                    src.getMessage("test", FI));
        }

        @Test
        @DisplayName("requested en → raw English pattern returned unchanged")
        void en() {
            MessageSource src = source(FI, "messages");
            assertEquals(
                    "Test amount {0,number,#,###.00} at date {1,date,dd/MM/yyyy} and time {2,time,HH:mm}",
                    src.getMessage("test", EN));
        }

        @Test
        @DisplayName("requested sv → raw Swedish pattern returned unchanged")
        void sv() {
            MessageSource src = source(FI, "messages");
            assertEquals(
                    "Testsumma {0,number,#,###.00} den {1,date,dd.MM.yyyy} kl {2,time,HH:mm}",
                    src.getMessage("test", SV));
        }
    }

    @Test
    @DisplayName("FR-LOC-080a: alwaysUseMessageFormat=true round-trips pattern through MessageFormat even with no args")
    void alwaysUseMessageFormatNoArgs() {
        // Pattern in messages_en.properties: quoted=Hello '{name}'
        // With alwaysUseMessageFormat=true, MessageFormat unescapes '{' → output is Hello {name}
        MessageSource src = factoryWithDefault(EN, false, false, true).create("messages");

        assertEquals("Hello {name}", src.getMessage("quoted", EN));
    }

    @Test
    @DisplayName("FR-LOC-080: alwaysUseMessageFormat=false leaves quoted pattern literal")
    void noFormatNoArgsLeavesQuotedLiteral() {
        MessageSource src = source(EN, "messages");
        // Without round-tripping through MessageFormat the raw pattern's "'{name}'" stays escaped.
        assertEquals("Hello '{name}'", src.getMessage("quoted", EN));
    }

    @Nested
    @DisplayName("FR-LOC-085/086/086a: findMessage")
    class FindMessage {

        @Test
        @DisplayName("Missing code with useCodeAsDefaultMessage=true returns Optional.empty (FR-LOC-086a)")
        void missingCodeWithCodeAsDefaultStillEmpty() {
            // useCodeAsDefaultMessage flag MUST NOT affect findMessage — it's reserved for the throwing overloads.
            MessageSource src = factoryWithDefault(EN, false, true, false).create("messages");

            Optional<String> result = src.findMessage("does.not.exist", EN);
            assertTrue(result.isEmpty(), "findMessage ignores useCodeAsDefaultMessage flag");
        }

        @Test
        @DisplayName("Missing code with useCodeAsDefaultMessage=false returns Optional.empty")
        void missingCodeNoCodeAsDefault() {
            MessageSource src = source(EN, "messages");
            assertTrue(src.findMessage("does.not.exist", EN).isEmpty());
        }

        @Test
        @DisplayName("Null code throws NullPointerException (FR-LOC-086)")
        void nullCodeThrowsNpe() {
            MessageSource src = source(EN, "messages");
            assertThrows(NullPointerException.class, () -> src.findMessage(null, EN));
        }

        @Test
        @DisplayName("Blank code throws NoSuchMessageException (FR-LOC-062 alignment)")
        void blankCodeThrows() {
            MessageSource src = source(EN, "messages");
            assertThrows(NoSuchMessageException.class, () -> src.findMessage("  ", EN));
        }

        @Test
        @DisplayName("Existing code with no args returns raw pattern wrapped in Optional")
        void existingCodeNoArgs() {
            MessageSource src = source(EN, "messages");
            assertEquals(Optional.of("Plain text"), src.findMessage("plain", EN));
        }
    }

    @Test
    @DisplayName("FR-LOC-071: useCodeAsDefaultMessage=true returns the code from the throwing overload")
    void useCodeAsDefaultMessageReturnsCode() {
        MessageSource src = factoryWithDefault(EN, false, true, false).create("messages");

        assertEquals("does.not.exist", src.getMessage("does.not.exist", EN));
    }

    @Test
    @DisplayName("FR-LOC-070: defaultMessage takes precedence over useCodeAsDefaultMessage")
    void defaultMessageWinsOverCodeAsDefault() {
        MessageSource src = factoryWithDefault(EN, false, true, false).create("messages");

        assertEquals("fallback", src.getMessage("does.not.exist", "fallback", EN));
    }

    @Test
    @DisplayName("FR-LOC-070: defaultMessage with args is formatted through MessageFormat")
    void defaultMessageFormatted() {
        MessageSource src = source(EN, "messages");

        assertEquals("Hello Alice", src.getMessage("missing", "Hello {0}", EN, "Alice"));
    }

    @Test
    @DisplayName("FR-LOC-072: missing code with no defaultMessage and no code-as-default throws NoSuchMessageException")
    void missingCodeThrows() {
        MessageSource src = source(EN, "messages");

        assertThrows(NoSuchMessageException.class, () -> src.getMessage("does.not.exist", EN));
    }

    @Test
    @DisplayName("FR-LOC-068: ROOT bundle (messages.properties) provides codes not in any locale variant")
    void rootBundleEntry() {
        MessageSource src = source(EN, "messages");

        // root-only key lives only in messages.properties (no _xx suffix)
        assertEquals("Root bundle entry only", src.getMessage("root-only", EN));
    }

    @Test
    @DisplayName("Primary basename wins over fallback when both contain the code")
    void primaryWinsOverFallback() {
        MessageSource src = factoryWithDefault(EN, false, false, false)
                .create(MessageSourceOptions.builder()
                        .basename("messages")
                        .fallbackBasename("fallback-common-messages")
                        .build());

        // 'shared' lives in both bundles; primary value wins.
        assertEquals("From primary", src.getMessage("shared", EN));
    }

    @Test
    @DisplayName("Fallback basename used when primary lacks the code")
    void fallbackUsedWhenPrimaryLacksCode() {
        MessageSource src = factoryWithDefault(EN, false, false, false)
                .create(MessageSourceOptions.builder()
                        .basename("messages")
                        .fallbackBasename("fallback-common-messages")
                        .build());

        assertEquals("From the fallback bundle", src.getMessage("fallback-only", EN));
    }

    @Test
    @DisplayName("FR-LOC-069: missing variant locale falls through to default-locale candidate")
    void missingVariantFallsThrough() {
        // No messages_de bundle exists; default locale is fi; greet has only fi/en/sv entries.
        // Request de → no de bundle → falls to fi (default) → returns Finnish.
        MessageSource src = source(FI, "messages");

        assertEquals("Hei Alice", src.getMessage("greet", Locale.GERMAN, "Alice"));
    }

    @Test
    @DisplayName("FR-LOC-087..089: MessageResolvable iterates codes in order and uses first hit")
    void resolvableFirstMatchWins() {
        MessageSource src = source(EN, "messages");

        MessageResolvable resolvable =
                new MessageResolvable(List.of("missing.one", "greet", "missing.two"), List.of("World"), null);

        assertEquals("Hello World", src.getMessage(resolvable, EN));
    }

    @Test
    @DisplayName("FR-LOC-088: MessageResolvable.defaultMessage used when no code matches")
    void resolvableDefaultMessage() {
        MessageSource src = source(EN, "messages");

        MessageResolvable resolvable =
                new MessageResolvable(List.of("missing.one", "missing.two"), List.of("Alice"), "Fallback {0}");

        assertEquals("Fallback Alice", src.getMessage(resolvable, EN));
    }

    @Test
    @DisplayName("FR-LOC-089: MessageResolvable with no match and no default throws NoSuchMessageException")
    void resolvableNoMatchNoDefaultThrows() {
        MessageSource src = source(EN, "messages");

        MessageResolvable resolvable = new MessageResolvable(List.of("missing.one", "missing.two"), List.of(), null);

        assertThrows(NoSuchMessageException.class, () -> src.getMessage(resolvable, EN));
    }

    @Test
    @DisplayName("FR-LOC-083: null args treated like no args (no MessageFormat invocation)")
    void nullArgsTreatedAsNoArgs() {
        MessageSource src = source(EN, "messages");

        // greet=Hello {0} — with null args, FR-LOC-080 returns raw pattern unchanged
        assertEquals("Hello {0}", src.getMessage("greet", EN, (Object[]) null));
    }

    @Test
    @DisplayName("FR-LOC-061..063: null code → NPE")
    void nullCodeThrowsNpe() {
        MessageSource src = source(EN, "messages");
        assertThrows(NullPointerException.class, () -> src.getMessage(null, EN));
    }

    @Test
    @DisplayName("FR-LOC-062: blank code → NoSuchMessageException")
    void blankCodeThrows() {
        MessageSource src = source(EN, "messages");
        assertThrows(NoSuchMessageException.class, () -> src.getMessage("   ", EN));
    }

    @Test
    @DisplayName("FR-LOC-063: null locale → NPE")
    void nullLocaleThrowsNpe() {
        MessageSource src = source(EN, "messages");
        assertThrows(NullPointerException.class, () -> src.getMessage("plain", (Locale) null));
    }

    @Test
    @DisplayName(
            "Missing bundle (only fi/en/sv exist) does not fail immediately — falls through to default-locale chain")
    void missingBundleNoFailureImmediately() {
        // Request a locale with no bundle (de) — should not throw; should resolve via default.
        MessageSource src = source(EN, "messages");
        assertEquals("Plain text", src.getMessage("plain", Locale.GERMAN));
    }

    @Test
    @DisplayName(
            "FR-LOC-068: base-bundle-only app — defaultLocale=en, supportedLocales=[en], no _en variant — must resolve from ROOT")
    void baseBundleOnlyDefaultLocale() {
        // App ships ONLY root-only-messages.properties (no _en variant). Candidate chain is [en]
        // (the single supported locale), and JDK falls back to ROOT because no messages_en exists.
        // The ROOT-skip filter must allow ROOT to match when the current candidate is the LAST in
        // the chain — otherwise this perfectly valid setup would throw NoSuchMessageException.
        LocalizationConfig config =
                new LocalizationConfig(EN, ZoneId.of("Europe/Helsinki"), List.of(EN), false, false, false, -1L);
        MessageSource src = new DefaultMessageSourceFactory(config).create("root-only-messages");

        assertEquals("Only entry in the ROOT bundle", src.getMessage("only", EN));
        assertEquals("Hello from base bundle", src.getMessage("greeting", EN));
    }

    @Test
    @DisplayName("System fallback must NOT steal precedence from configured-default ROOT (FR-LOC-064)")
    void configuredDefaultRootBeatsSystemFallbackSpecificBundle() {
        // FR-LOC-064 priority: requested → configured default → system fallback. When the
        // configured default has only the ROOT base bundle (no defaultLocale-specific variant)
        // AND the system locale happens to have its own bundle, the configured default's ROOT
        // match must win — system fallback is strictly last-resort.
        //
        // Uses the dedicated 'root-only-messages' basename so the test is isolated from the
        // shared 'messages' fixtures which DO have an _en variant:
        //   root-only-messages.properties     (ROOT — "only=Only entry in the ROOT bundle")
        //   root-only-messages_fi.properties  (Finnish — "only=Vain ROOT-bundlessa")
        // No _en variant exists. defaultLocale=en, supportedLocales=[en],
        // fallbackToSystemLocale=true, JVM default forced to fi (so the system-fallback
        // candidate is fi and has its own bundle).
        // Expected: lookup 'only' for 'en' returns the ROOT bundle text, NOT the Finnish text.
        Locale priorDefault = Locale.getDefault();
        try {
            Locale.setDefault(FI);
            LocalizationConfig config =
                    new LocalizationConfig(EN, ZoneId.of("Europe/Helsinki"), List.of(EN), true, false, false, -1L);
            MessageSource src = new DefaultMessageSourceFactory(config).create("root-only-messages");

            assertEquals("Only entry in the ROOT bundle", src.getMessage("only", EN));
        } finally {
            Locale.setDefault(priorDefault);
        }
    }

    @Test
    @DisplayName(
            "ROOT-skip still applies for intermediate candidates: requested locale has no bundle, default falls through")
    void rootSkipPreservedForIntermediateCandidate() {
        // requested=de, defaultLocale=fi, supportedLocales=[fi, sv, en], basename=messages.
        // Candidate chain: [de, fi]. For 'de' the JDK falls back to ROOT (messages.properties
        // exists in this test setup); the filter MUST skip that ROOT match so the walk reaches
        // 'fi' and uses messages_fi.properties instead. This is the original ROOT-skip behavior
        // and the new "last candidate" carve-out must not regress it.
        MessageSource src = source(FI, "messages");

        // 'greet' lives in messages_fi.properties → must return Finnish, not the ROOT 'Default root text'.
        assertEquals("Hei Alice", src.getMessage("greet", Locale.GERMAN, "Alice"));
    }

    @Test
    @DisplayName("MessageResolvable with null arg element formats as 'null'")
    void resolvableNullArgElement() {
        MessageSource src = source(EN, "messages");

        MessageResolvable resolvable = new MessageResolvable(List.of("greet"), Arrays.asList((Object) null), null);

        assertEquals("Hello null", src.getMessage(resolvable, EN));
    }
}
