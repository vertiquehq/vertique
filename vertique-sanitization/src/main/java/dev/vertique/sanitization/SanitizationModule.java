// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.CanonicalizerBinding;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SanitizerBinding;
import dev.vertique.rest.core.request.DefaultInputObjectProcessor;
import dev.vertique.rest.core.request.InputObjectProcessor;
import dev.vertique.rest.core.request.InputPolicyMetadataResolver;
import dev.vertique.sanitization.canonicalize.CollapseWhitespaceCanonicalizer;
import dev.vertique.sanitization.canonicalize.LowerCaseCanonicalizer;
import dev.vertique.sanitization.canonicalize.NfcCanonicalizer;
import dev.vertique.sanitization.canonicalize.NfkcCanonicalizer;
import dev.vertique.sanitization.canonicalize.NormalizeLineEndingsCanonicalizer;
import dev.vertique.sanitization.canonicalize.RemoveIdentifierSeparatorsCanonicalizer;
import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
import dev.vertique.sanitization.canonicalize.UpperCaseCanonicalizer;
import dev.vertique.sanitization.sanitize.BasicHtmlSanitizer;
import dev.vertique.sanitization.sanitize.LinksHtmlSanitizer;
import dev.vertique.sanitization.sanitize.RichTextHtmlSanitizer;
import dev.vertique.sanitization.sanitize.StripAllHtmlSanitizer;
import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module that registers all built-in {@link Canonicalizer} and {@link Sanitizer}
 * implementations as multibindings and wires up the {@link ProcessorResolver}.
 *
 * <p>Include this module in your Dagger component to enable sanitization:
 * <pre>{@code
 * @Component(modules = {VertxModule.class, RestModule.class, SanitizationModule.class, ...})
 * public interface AppComponent { ... }
 * }</pre>
 *
 * <p>Built-in canonicalizers registered:
 * <ul>
 *   <li>{@link TrimCanonicalizer}</li>
 *   <li>{@link NfkcCanonicalizer}</li>
 *   <li>{@link NfcCanonicalizer}</li>
 *   <li>{@link NormalizeLineEndingsCanonicalizer}</li>
 *   <li>{@link CollapseWhitespaceCanonicalizer}</li>
 *   <li>{@link UpperCaseCanonicalizer}</li>
 *   <li>{@link LowerCaseCanonicalizer}</li>
 *   <li>{@link RemoveIdentifierSeparatorsCanonicalizer}</li>
 * </ul>
 *
 * <p>Built-in sanitizers registered:
 * <ul>
 *   <li>{@link StripControlCharsSanitizer}</li>
 *   <li>{@link StripAllHtmlSanitizer}</li>
 *   <li>{@link BasicHtmlSanitizer}</li>
 *   <li>{@link LinksHtmlSanitizer}</li>
 *   <li>{@link RichTextHtmlSanitizer}</li>
 * </ul>
 *
 * <p>Extension points — add custom processors via:
 * <pre>{@code
 * @Provides @IntoSet
 * static CanonicalizerBinding myCanonicalizer(MyCanonicalizer c) {
 *     return new CanonicalizerBinding(MyCanonicalizer.class, c);
 * }
 * }</pre>
 */
@Module
public abstract class SanitizationModule {

    // --- Multibinding declarations ---

    /**
     * Declares the multibinding set for {@link CanonicalizerBinding} contributions.
     *
     * @return an empty set (populated by {@code @IntoSet} and {@code @ElementsIntoSet} contributions)
     */
    @Multibinds
    abstract Set<CanonicalizerBinding> canonicalizerBindings();

    /**
     * Declares the multibinding set for {@link SanitizerBinding} contributions.
     *
     * @return an empty set (populated by {@code @IntoSet} and {@code @ElementsIntoSet} contributions)
     */
    @Multibinds
    abstract Set<SanitizerBinding> sanitizerBindings();

    // --- Built-in canonicalizer registrations ---

    /**
     * Provides the set of all built-in canonicalizer bindings.
     *
     * @return the set of built-in {@link CanonicalizerBinding} instances
     */
    @Provides
    @ElementsIntoSet
    static Set<CanonicalizerBinding> builtInCanonicalizerBindings() {
        return Set.of(
                new CanonicalizerBinding(TrimCanonicalizer.class, new TrimCanonicalizer()),
                new CanonicalizerBinding(NfkcCanonicalizer.class, new NfkcCanonicalizer()),
                new CanonicalizerBinding(NfcCanonicalizer.class, new NfcCanonicalizer()),
                new CanonicalizerBinding(
                        NormalizeLineEndingsCanonicalizer.class, new NormalizeLineEndingsCanonicalizer()),
                new CanonicalizerBinding(CollapseWhitespaceCanonicalizer.class, new CollapseWhitespaceCanonicalizer()),
                new CanonicalizerBinding(UpperCaseCanonicalizer.class, new UpperCaseCanonicalizer()),
                new CanonicalizerBinding(LowerCaseCanonicalizer.class, new LowerCaseCanonicalizer()),
                new CanonicalizerBinding(
                        RemoveIdentifierSeparatorsCanonicalizer.class, new RemoveIdentifierSeparatorsCanonicalizer()));
    }

    // --- Built-in sanitizer registrations ---

    /**
     * Provides the set of all built-in sanitizer bindings.
     *
     * @return the set of built-in {@link SanitizerBinding} instances
     */
    @Provides
    @ElementsIntoSet
    static Set<SanitizerBinding> builtInSanitizerBindings() {
        return Set.of(
                new SanitizerBinding(StripControlCharsSanitizer.class, new StripControlCharsSanitizer()),
                new SanitizerBinding(StripAllHtmlSanitizer.class, new StripAllHtmlSanitizer()),
                new SanitizerBinding(BasicHtmlSanitizer.class, new BasicHtmlSanitizer()),
                new SanitizerBinding(LinksHtmlSanitizer.class, new LinksHtmlSanitizer()),
                new SanitizerBinding(RichTextHtmlSanitizer.class, new RichTextHtmlSanitizer()));
    }

    // --- Input processing wiring ---

    /**
     * Provides the {@link InputPolicyMetadataResolver} used by {@link DefaultInputObjectProcessor}
     * to resolve and cache per-type annotation metadata.
     *
     * @return a new singleton metadata resolver
     */
    @Provides
    @Singleton
    static InputPolicyMetadataResolver inputPolicyMetadataResolver() {
        return new InputPolicyMetadataResolver();
    }

    /**
     * Provides the {@link InputObjectProcessor} that applies canonicalization and sanitization
     * to structured request bodies.
     *
     * <p>This binding satisfies the {@code @BindsOptionalOf InputObjectProcessor} declared in
     * {@code RestModule} — when {@code SanitizationModule} is included in the Dagger component,
     * input processing is active.
     *
     * @param metadataResolver  resolves and caches per-type annotation metadata
     * @param processorResolver resolves canonicalizer and sanitizer instances by class
     * @return a fully wired {@link DefaultInputObjectProcessor}
     */
    @Provides
    @Singleton
    static InputObjectProcessor inputObjectProcessor(
            InputPolicyMetadataResolver metadataResolver, ProcessorResolver processorResolver) {
        return new DefaultInputObjectProcessor(
                metadataResolver, processorResolver::resolveCanonicalizer, processorResolver::resolveSanitizer);
    }
}
