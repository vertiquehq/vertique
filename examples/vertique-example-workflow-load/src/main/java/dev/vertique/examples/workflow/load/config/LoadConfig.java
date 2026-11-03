// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.Set;
import java.util.TreeSet;

/**
 * The settings that belong to the load harness itself, read from the {@code load} section of the node
 * configuration.
 *
 * <p>Product settings keep their product keys. This section holds only what the harness adds: how long the
 * dispatch handler waits before it records its callback, how long the workload's timer step lasts, and where the
 * loopback ingress listens.
 *
 * <p>Every node of a run must use the same {@code timerDelayMs}: the timer length is part of the definition, so a
 * node that disagrees builds a different plan and cannot run the commands of the others.
 *
 * <p>The section is checked strictly. {@link #parse(JsonObject, ConfigParser)} rejects a key it does not know, so a
 * misspelled setting fails the node at startup instead of running with a default.
 *
 * <pre>{@code
 * {
 *   "load": {
 *     "handlerDelayMs": 20,
 *     "timerDelayMs": 1000,
 *     "ingress": { "host": "127.0.0.1", "port": 0, "portFile": "/tmp/node-1.port" }
 *   }
 * }
 * }</pre>
 *
 * @param handlerDelayMs how long the dispatch handler waits on a Vert.x timer before it records the callback, in
 *     milliseconds; from 0 to {@value #MAX_HANDLER_DELAY_MS}. Zero records the callback without a timer
 * @param timerDelayMs the length of the workload's timer step, in milliseconds; from 1 to
 *     {@value #MAX_TIMER_DELAY_MS}
 * @param ingress where the loopback ingress listens
 */
public record LoadConfig(long handlerDelayMs, long timerDelayMs, Ingress ingress) {

    /** The default handler delay, in milliseconds. */
    public static final long DEFAULT_HANDLER_DELAY_MS = 20L;

    /** The longest accepted handler delay, in milliseconds; it stays below the relay's claim lease. */
    public static final long MAX_HANDLER_DELAY_MS = 10_000L;

    /** The default length of the timer step, in milliseconds. */
    public static final long DEFAULT_TIMER_DELAY_MS = 1_000L;

    /** The longest accepted length of the timer step, in milliseconds. */
    public static final long MAX_TIMER_DELAY_MS = 600_000L;

    private static final Set<String> KEYS = Set.of("handlerDelayMs", "timerDelayMs", "ingress");
    private static final Set<String> INGRESS_KEYS = Set.of("host", "port", "portFile");

    /**
     * Validates the settings.
     *
     * @throws ConfigurationException naming the offending key if a value is outside its bound
     */
    public LoadConfig {
        if (handlerDelayMs < 0 || handlerDelayMs > MAX_HANDLER_DELAY_MS) {
            throw new ConfigurationException(
                    "load.handlerDelayMs must be between 0 and " + MAX_HANDLER_DELAY_MS + " but was " + handlerDelayMs);
        }
        if (timerDelayMs < 1 || timerDelayMs > MAX_TIMER_DELAY_MS) {
            throw new ConfigurationException(
                    "load.timerDelayMs must be between 1 and " + MAX_TIMER_DELAY_MS + " but was " + timerDelayMs);
        }
        if (ingress == null) {
            ingress = Ingress.defaults();
        }
    }

    /**
     * Where the loopback ingress listens.
     *
     * @param host the address to bind; non-blank. The default is the loopback address
     * @param port the port to bind, or 0 to let the system choose one; from 0 to 65535. A node that binds port 0
     *     reports the port it got through {@code portFile}
     * @param portFile a file the node writes the bound port to once the ingress listens, or {@code null} for none
     */
    public record Ingress(String host, int port, @Nullable String portFile) {

        /** The default bind address. */
        public static final String DEFAULT_HOST = "127.0.0.1";

        /**
         * Validates the settings.
         *
         * @throws ConfigurationException naming the offending key if a value is outside its bound
         */
        public Ingress {
            if (host == null || host.isBlank()) {
                throw new ConfigurationException("load.ingress.host must not be blank");
            }
            if (port < 0 || port > 65_535) {
                throw new ConfigurationException("load.ingress.port must be between 0 and 65535 but was " + port);
            }
            if (portFile != null && portFile.isBlank()) {
                throw new ConfigurationException("load.ingress.portFile must not be blank when it is set");
            }
        }

        @JsonCreator
        static Ingress fromJson(
                @JsonProperty("host") @Nullable String host,
                @JsonProperty("port") @Nullable Integer port,
                @JsonProperty("portFile") @Nullable String portFile) {
            return new Ingress(host == null ? DEFAULT_HOST : host, port == null ? 0 : port, portFile);
        }

        /**
         * Returns the ingress settings that apply when none are supplied.
         *
         * @return the loopback address, a system-chosen port and no port file
         */
        public static Ingress defaults() {
            return new Ingress(DEFAULT_HOST, 0, null);
        }
    }

    @JsonCreator
    static LoadConfig fromJson(
            @JsonProperty("handlerDelayMs") @Nullable Long handlerDelayMs,
            @JsonProperty("timerDelayMs") @Nullable Long timerDelayMs,
            @JsonProperty("ingress") @Nullable Ingress ingress) {
        return new LoadConfig(
                handlerDelayMs == null ? DEFAULT_HANDLER_DELAY_MS : handlerDelayMs,
                timerDelayMs == null ? DEFAULT_TIMER_DELAY_MS : timerDelayMs,
                ingress);
    }

    /**
     * Returns the settings that apply when the section is absent.
     *
     * @return a 20 ms handler delay, a 1 s timer step and the default ingress
     */
    public static LoadConfig defaults() {
        return new LoadConfig(DEFAULT_HANDLER_DELAY_MS, DEFAULT_TIMER_DELAY_MS, Ingress.defaults());
    }

    /**
     * Parses the {@code load} section, rejecting any key it does not know.
     *
     * @param section the {@code load} section of the node configuration; empty when the section is absent
     * @param parser the canonical configuration parser
     * @return the validated settings
     * @throws ConfigurationException naming the key if the section or its {@code ingress} object holds an unknown key
     *     or a value outside its bound
     */
    public static LoadConfig parse(JsonObject section, ConfigParser parser) {
        rejectUnknown(section, "load", KEYS);
        if (section.getValue("ingress") instanceof JsonObject ingress) {
            rejectUnknown(ingress, "load.ingress", INGRESS_KEYS);
        }
        return parser.parse(section, LoadConfig.class);
    }

    private static void rejectUnknown(JsonObject section, String path, Set<String> known) {
        Set<String> unknown = new TreeSet<>(section.fieldNames());
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw new ConfigurationException(path + "." + unknown.iterator().next()
                    + " is not a setting of the load harness; known settings are " + new TreeSet<>(known));
        }
    }
}
