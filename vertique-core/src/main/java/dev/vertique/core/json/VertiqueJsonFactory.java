// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import io.vertx.core.spi.JsonFactory;
import io.vertx.core.spi.json.JsonCodec;

/**
 * Registers the framework's JSON codec as the Vert.x process codec.
 *
 * <p>Vert.x discovers this factory through the service file
 * {@code META-INF/services/io.vertx.core.spi.JsonFactory} on the thread-context classloader, sorts
 * every discovered factory by ascending {@link #order()}, and assigns the winner's
 * {@link #codec() codec} to {@code Json.CODEC} once, when {@code io.vertx.core.json.Json} is
 * initialized. Vert.x's own Jackson codec is used only when no factory is registered at all.
 *
 * <p>Two packaging consequences follow, both of which
 * {@link VertiqueJson#ownsCodec()} makes observable at runtime:
 *
 * <ul>
 *   <li>The service file must survive packaging. A shaded or uber-jar build has to merge service
 *       files (Maven Shade's {@code ServicesResourceTransformer}, or the equivalent for the
 *       packaging tool in use); dropping them silently leaves Vert.x on its own codec.</li>
 *   <li>The framework must be visible to the classloader that initializes {@code Json} — the
 *       thread-context classloader at that moment. A container that isolates the framework from the
 *       application classloader can hide the registration.</li>
 * </ul>
 *
 * <p>The {@link #order()} value is low enough that an application which registers its own factory
 * at the SPI's default order does not displace the framework codec.
 */
public final class VertiqueJsonFactory implements JsonFactory {

    /** Public no-argument constructor required by the {@link java.util.ServiceLoader}. */
    public VertiqueJsonFactory() {}

    /**
     * Returns this factory's selection order. Vert.x picks the registered factory with the lowest
     * value.
     *
     * @return the framework's fixed selection order
     */
    @Override
    public int order() {
        return -1000;
    }

    /**
     * Returns the process codec, whose databind operations run on the mapper installed through
     * {@link VertiqueJson#install}.
     *
     * @return the framework's process JSON codec; never {@code null}
     */
    @Override
    public JsonCodec codec() {
        return VertiqueJsonCodec.INSTANCE;
    }
}
