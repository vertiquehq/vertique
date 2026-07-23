// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.localization.message;

import dev.vertique.localization.config.LocalizationConfig;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Default, immutable implementation of {@link MessageSource} backed by Java {@link ResourceBundle}.
 *
 * <p>Instances are produced exclusively by {@link DefaultMessageSourceFactory#create(MessageSourceOptions)}.
 * Once constructed, this class is safe to share across threads because all fields are final
 * and {@link MessageFormat} instances are created per-call rather than shared (NFR-LOC-001,
 * NFR-LOC-002).
 *
 * <h2>Candidate-locale ordering (FR-LOC-064)</h2>
 * <ol>
 *   <li>The requested locale.</li>
 *   <li>The configured {@linkplain LocalizationConfig#defaultLocale() default locale}, when
 *       different from the requested locale.</li>
 *   <li>{@link Locale#getDefault()}, only when
 *       {@linkplain LocalizationConfig#fallbackToSystemLocale()} is {@code true} and it differs
 *       from both prior candidates. Evaluated per-call, never cached at construction (PRD §12).</li>
 * </ol>
 *
 * <h2>Basename ordering (FR-LOC-065)</h2>
 * <p>For each candidate locale: primary basename first, then fallback basenames in declaration
 * order. The first bundle that contains the code wins (FR-LOC-066). A missing bundle does not
 * terminate the search; later candidates are always tried (FR-LOC-069).
 *
 * <h2>Missing-message resolution (FR-LOC-070..072)</h2>
 * <ul>
 *   <li>{@code defaultMessage != null} → format that string and return it.</li>
 *   <li>{@link LocalizationConfig#useCodeAsDefaultMessage()} is {@code true} (throwing overloads
 *       only) → return the code as-is.</li>
 *   <li>Otherwise → throw {@link NoSuchMessageException}.</li>
 * </ul>
 *
 * @see DefaultMessageSourceFactory
 * @see MessageSource
 * @see BundleControl
 */
class DefaultMessageSource implements MessageSource {

    // --- Fields ---

    private final Locale defaultLocale;
    private final boolean fallbackToSystemLocale;
    private final boolean useCodeAsDefaultMessage;
    private final boolean alwaysUseMessageFormat;

    private final String primaryBasename;

    /** Precomputed ordered basename chain (primary first, then declared fallbacks). */
    private final List<String> basenames;

    /** Resolved classloader for ResourceBundle lookups; set by the factory. */
    private final ClassLoader classLoader;

    /**
     * Stateless bundle-control shared across all lookups in this source instance.
     * One instance per {@code DefaultMessageSource} is required to preserve the JDK bundle cache
     * keyed by {@code (classloader, basename, locale, control)}.
     */
    private final BundleControl bundleControl;

    // --- Constructor ---

    /**
     * Package-private constructor called exclusively by {@link DefaultMessageSourceFactory}.
     *
     * @param config      the module configuration; must not be {@code null}
     * @param options     the resolved options for this source; must not be {@code null}
     * @param classLoader the classloader resolved by the factory; must not be {@code null}
     */
    DefaultMessageSource(LocalizationConfig config, MessageSourceOptions options, ClassLoader classLoader) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(options, "options");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");

        this.defaultLocale = config.defaultLocale();
        this.fallbackToSystemLocale = config.fallbackToSystemLocale();
        this.useCodeAsDefaultMessage = config.useCodeAsDefaultMessage();
        this.alwaysUseMessageFormat = config.alwaysUseMessageFormat();

        this.primaryBasename = options.basename();
        List<String> chain = new ArrayList<>(1 + options.fallbackBasenames().size());
        chain.add(this.primaryBasename);
        chain.addAll(options.fallbackBasenames());
        this.basenames = List.copyOf(chain);

        this.bundleControl = new BundleControl(config.cacheTtlSeconds());
    }

    // --- MessageSource: throwing overloads ---

    /**
     * {@inheritDoc}
     *
     * <p>Validates {@code code} (null → NPE; blank → {@link NoSuchMessageException}) and
     * {@code locale} (null → NPE), then walks the candidate-locale and basename chains.
     */
    @Override
    public String getMessage(String code, Locale locale, Object... args) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(locale, "locale");
        validateCode(code);

        String pattern = resolvePattern(code, locale);
        if (pattern != null) {
            return format(pattern, locale, args);
        }

        // No pattern found — apply missing-message resolution
        if (useCodeAsDefaultMessage) {
            return applyCodeAsDefault(code, locale, args);
        }
        throw noSuchMessage(code);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code defaultMessage} formatted with the supplied args when the code cannot be
     * resolved (FR-LOC-070, FR-LOC-084).
     */
    @Override
    public String getMessage(String code, String defaultMessage, Locale locale, Object... args) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(locale, "locale");
        validateCode(code);

        String pattern = resolvePattern(code, locale);
        if (pattern != null) {
            return format(pattern, locale, args);
        }

        // No pattern found — defaultMessage wins over useCodeAsDefaultMessage (FR-LOC-070)
        if (defaultMessage != null) {
            return format(defaultMessage, locale, args);
        }
        if (useCodeAsDefaultMessage) {
            return applyCodeAsDefault(code, locale, args);
        }
        throw noSuchMessage(code);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code findMessage} never applies {@code useCodeAsDefaultMessage}; it returns
     * {@link Optional#empty()} whenever no bundle entry exists for the code,
     * regardless of the config flag (FR-LOC-086a).
     */
    @Override
    public Optional<String> findMessage(String code, Locale locale, Object... args) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(locale, "locale");
        validateCode(code);

        String pattern = resolvePattern(code, locale);
        if (pattern != null) {
            return Optional.of(format(pattern, locale, args));
        }
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Iterates {@link MessageResolvable#codes()} in order via {@link #findMessage} (which does
     * not apply {@code useCodeAsDefaultMessage}). Falls back to
     * {@link MessageResolvable#defaultMessage()} if present, or throws for the first code
     * (FR-LOC-087..089).
     */
    @Override
    public String getMessage(MessageResolvable resolvable, Locale locale) {
        Objects.requireNonNull(resolvable, "resolvable");
        Objects.requireNonNull(locale, "locale");

        Object[] args = resolvable.args().toArray();

        // Try each code in order (FR-LOC-087)
        for (String code : resolvable.codes()) {
            Optional<String> found = findMessage(code, locale, args);
            if (found.isPresent()) {
                return found.get();
            }
        }

        // No code matched — use defaultMessage if available (FR-LOC-088)
        if (resolvable.defaultMessage() != null) {
            return format(resolvable.defaultMessage(), locale, args);
        }

        // No match and no default — throw for the first code (FR-LOC-089)
        throw noSuchMessage(resolvable.codes().get(0));
    }

    // --- Candidate-locale walk ---

    /**
     * Builds the ordered list of candidate locales for a single lookup (FR-LOC-064).
     *
     * <p>{@link Locale#getDefault()} is read per-call to capture dynamic JVM changes without
     * requiring a source rebuild (PRD §12 impl note).
     *
     * @param requestedLocale the locale supplied by the caller
     * @return an ordered, de-duplicated list of locales to attempt; never empty
     */
    private List<Locale> candidateLocales(Locale requestedLocale) {
        boolean defaultDiffers = !defaultLocale.equals(requestedLocale);

        if (!fallbackToSystemLocale) {
            return defaultDiffers ? List.of(requestedLocale, defaultLocale) : List.of(requestedLocale);
        }

        Locale systemLocale = Locale.getDefault(); // read per-call (PRD §12)
        boolean systemDiffers = !systemLocale.equals(requestedLocale) && !systemLocale.equals(defaultLocale);

        if (!defaultDiffers && !systemDiffers) {
            return List.of(requestedLocale);
        }
        if (!defaultDiffers) {
            return List.of(requestedLocale, systemLocale);
        }
        if (!systemDiffers) {
            return List.of(requestedLocale, defaultLocale);
        }
        return List.of(requestedLocale, defaultLocale, systemLocale);
    }

    // --- Bundle lookup ---

    /**
     * Resolves the raw message pattern for {@code code} by walking the candidate-locale chain and,
     * for each locale, the primary then fallback basenames (FR-LOC-065..069).
     *
     * <p>A {@link MissingResourceException} from a missing bundle or missing key does not fail
     * the search — the next basename or candidate locale is tried immediately (FR-LOC-069).
     *
     * @param code            the message code to look up
     * @param requestedLocale the locale requested by the caller
     * @return the raw pattern string, or {@code null} if not found in any candidate
     */
    private String resolvePattern(String code, Locale requestedLocale) {
        for (Locale candidate : candidateLocales(requestedLocale)) {
            // FR-LOC-064 priority: ROOT may satisfy only the configured-default candidate.
            // Earlier candidates (requested != default) and later candidates (system fallback)
            // never accept ROOT — otherwise the framework's prioritised candidate-walk would be
            // short-circuited by JDK's internal ROOT fallback for the wrong locale.
            boolean acceptRoot = candidate.equals(defaultLocale);
            for (String basename : basenames) {
                String pattern = lookupInBundle(basename, candidate, code, acceptRoot);
                if (pattern != null) {
                    return pattern;
                }
            }
        }
        return null;
    }

    /**
     * Attempts to retrieve a single message code from the specified bundle and locale.
     *
     * <p>Returns {@code null} if the bundle does not exist for the locale, or the code is absent
     * from the bundle (FR-LOC-069). Any {@link MissingResourceException} is caught and treated as
     * a miss so the outer loop can continue with the next basename or candidate locale.
     *
     * <p>When a non-ROOT candidate locale is requested and the JDK can only find the ROOT bundle
     * (i.e., no locale-specific bundle exists for that candidate), the behaviour depends on
     * {@code acceptRoot}:
     * <ul>
     *   <li>{@code acceptRoot=false} — the ROOT match is <em>skipped</em>. Used for any candidate
     *       that is not the configured {@code defaultLocale}: the framework's candidate-walk
     *       should reach the configured default before falling back to the base bundle, otherwise
     *       JDK's internal ROOT fallback would short-circuit the prioritised chain (FR-LOC-064 —
     *       system fallback must not steal precedence from the configured default).</li>
     *   <li>{@code acceptRoot=true} — the ROOT match is <em>accepted</em>. Used only for the
     *       configured-default candidate so a base-bundle-only deployment (only
     *       {@code messages.properties}, no locale-specific variant) resolves messages
     *       (FR-LOC-068).</li>
     * </ul>
     *
     * <p>The ROOT bundle remains reachable as the parent of any locale-specific bundle (via
     * {@link ResourceBundle#getString}'s parent-chain traversal), so codes absent from the
     * locale-specific file but present in {@code messages.properties} are still found for any
     * locale that HAS a locale-specific bundle (FR-LOC-068).
     *
     * @param basename    the resource bundle base name
     * @param locale      the candidate locale
     * @param code        the message code
     * @param acceptRoot  {@code true} when a ROOT-only match is acceptable for this candidate
     *                    (i.e. {@code locale.equals(defaultLocale)}); {@code false} otherwise
     * @return the raw pattern string, or {@code null} on any miss
     */
    private String lookupInBundle(String basename, Locale locale, String code, boolean acceptRoot) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(basename, locale, classLoader, bundleControl);
            // ROOT-fallback filter: skip the ROOT bundle unless the caller (configured-default
            // candidate) explicitly accepts it. Preserves FR-LOC-064 priority — system fallback
            // may not steal precedence from the configured default's ROOT match.
            if (!acceptRoot && !locale.equals(Locale.ROOT) && bundle.getLocale().equals(Locale.ROOT)) {
                return null;
            }
            return bundle.getString(code);
        } catch (MissingResourceException e) {
            return null;
        }
    }

    // --- Formatting ---

    /**
     * Formats the given {@code pattern} according to the formatting rules (FR-LOC-080..084).
     *
     * <ul>
     *   <li>If {@code args} is {@code null} or empty AND
     *       {@link LocalizationConfig#alwaysUseMessageFormat()} is {@code false}, return
     *       the raw pattern unchanged (FR-LOC-080).</li>
     *   <li>Otherwise, construct a new {@link MessageFormat} with the requested locale and apply it.
     *       A new instance is created per call — {@code MessageFormat} is not thread-safe
     *       (NFR-LOC-002, FR-LOC-082).</li>
     * </ul>
     *
     * @param pattern  the raw message pattern; must not be {@code null}
     * @param locale   the formatting locale (always the <em>requested</em> locale, FR-LOC-082)
     * @param args     optional format arguments; {@code null} is treated as no args (FR-LOC-083)
     * @return the formatted message string
     */
    private String format(String pattern, Locale locale, Object... args) {
        boolean hasArgs = args != null && args.length > 0;
        if (!hasArgs && !alwaysUseMessageFormat) {
            return pattern;
        }
        // Always use the requested locale for formatting (FR-LOC-082)
        MessageFormat mf = new MessageFormat(pattern, locale);
        Object[] safeArgs = (args == null) ? new Object[0] : args;
        return mf.format(safeArgs);
    }

    // --- Helpers ---

    /**
     * Validates that {@code code} is not blank; throws {@link NoSuchMessageException} if it is
     * (FR-LOC-062).
     *
     * @param code the code to validate; must be non-null (caller guarantees this)
     * @throws NoSuchMessageException if the code is blank
     */
    private void validateCode(String code) {
        if (code.isBlank()) {
            throw new NoSuchMessageException(primaryBasename, code, "Message code must not be blank");
        }
    }

    /**
     * Applies {@code useCodeAsDefaultMessage} semantics by returning the code string after routing
     * it through the formatter (so that quoting/apostrophe handling with
     * {@code alwaysUseMessageFormat} is consistent).
     *
     * @param code   the message code to return as the message text
     * @param locale the formatting locale
     * @param args   format arguments (typically empty for code-as-default path)
     * @return the code, possibly formatted
     */
    private String applyCodeAsDefault(String code, Locale locale, Object... args) {
        return format(code, locale, args);
    }

    /**
     * Constructs a {@link NoSuchMessageException} for a lookup miss on the primary basename.
     *
     * @param code the missing message code
     * @return a new exception capturing the primary basename and the missing code
     */
    private NoSuchMessageException noSuchMessage(String code) {
        return new NoSuchMessageException(primaryBasename, code, "No message found for code '" + code + "'");
    }
}
