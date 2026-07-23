// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.correlation;

import dev.vertique.core.correlation.CorrelationIdGenerator;
import java.util.UUID;

/**
 * Default {@link CorrelationIdGenerator} implementation that produces RFC 4122 UUID v4 strings.
 *
 * <p>Used by {@code CorrelationContextFactory} when the application has not contributed an
 * override via Dagger. Apps can override by providing their own {@link CorrelationIdGenerator}
 * binding (e.g. ULID/NanoID); this default is wired through {@code @BindsOptionalOf} in
 * {@code CorrelationContextModule} so an app-supplied generator wins without ambiguity.
 *
 * <p>Holds no state — the singleton {@link #INSTANCE} is sufficient. Not a {@code @Singleton}
 * Dagger binding because resolution goes through the {@code Optional<CorrelationIdGenerator>}
 * injection point on the factory; this class exists as a plain default.
 */
public final class Uuid4CorrelationIdGenerator implements CorrelationIdGenerator {

    /** Shared no-state instance. Use directly when reaching for the default outside DI. */
    public static final Uuid4CorrelationIdGenerator INSTANCE = new Uuid4CorrelationIdGenerator();

    private Uuid4CorrelationIdGenerator() {}

    @Override
    public String generate() {
        return UUID.randomUUID().toString();
    }
}
