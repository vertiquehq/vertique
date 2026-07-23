// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.logging.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.AppenderAttachableImpl;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Marker;

/**
 * A logback appender that routes log events to named child appenders based on SLF4J marker names,
 * falling back to a configured default appender when no marker matches.
 *
 * <p>This appender acts as a multiplexer: it holds a set of named child appenders and a default
 * appender name. For each incoming event, it inspects the event's marker list and routes the event
 * to the first child appender whose name matches a marker (or a marker reachable through the
 * SLF4J marker containment hierarchy). Events without markers, or with markers that match no child
 * appender, are routed to the default appender.
 *
 * <h2>Marker resolution strategy</h2>
 * <ol>
 *   <li>If the event has no markers, route to the default appender.</li>
 *   <li>For each marker in the event's marker list:
 *     <ol>
 *       <li><strong>Fast path</strong>: look up an appender whose name equals the marker's own name.</li>
 *       <li><strong>Recursive hierarchy</strong>: if the marker has references, check whether any
 *           attached appender's name is contained in the marker's hierarchy via
 *           {@link Marker#contains(String)}.</li>
 *     </ol>
 *   </li>
 *   <li>If no match is found across all markers, route to the default appender.</li>
 * </ol>
 *
 * <p>Exactly one child appender receives each event; this is not a broadcast appender.
 *
 * <h2>Startup validation</h2>
 * {@link #start()} fails (and logs an error) if:
 * <ul>
 *   <li>No {@code <appender-ref>} elements are configured</li>
 *   <li>{@code <defaultAppender>} is not set or is empty</li>
 *   <li>The named default appender is not found among the attached appender-refs</li>
 *   <li>Any attached appender-ref resolves to {@code this} (self-recursion guard)</li>
 * </ul>
 *
 * <h2>Example logback.xml configuration</h2>
 * <pre>{@code
 * <configuration>
 *
 *   <!-- Technical (default) appender -->
 *   <appender name="TECH" class="ch.qos.logback.core.ConsoleAppender">
 *     <encoder><pattern>%d %-5level %logger - %msg%n</pattern></encoder>
 *   </appender>
 *
 *   <!-- Audit appender (receives events with the AUDIT marker) -->
 *   <appender name="AUDIT" class="ch.qos.logback.core.FileAppender">
 *     <file>audit.log</file>
 *     <encoder><pattern>%d %msg%n</pattern></encoder>
 *   </appender>
 *
 *   <!-- Marker-aware router -->
 *   <appender name="ROUTER" class="dev.vertique.logging.logback.MarkerAwareAppender">
 *     <defaultAppender>TECH</defaultAppender>
 *     <appender-ref ref="TECH"/>
 *     <appender-ref ref="AUDIT"/>
 *   </appender>
 *
 *   <root level="INFO">
 *     <appender-ref ref="ROUTER"/>
 *   </root>
 *
 * </configuration>
 * }</pre>
 *
 * @see VertxAwareAppender
 */
public class MarkerAwareAppender extends UnsynchronizedAppenderBase<ILoggingEvent>
        implements AppenderAttachable<ILoggingEvent> {

    // --- State ---

    private final AppenderAttachableImpl<ILoggingEvent> aai = new AppenderAttachableImpl<>();

    /** Name of the fallback appender, as configured via {@code <defaultAppender>} in XML. */
    private String defaultAppenderName;

    /** Resolved reference to the default appender, populated during {@link #start()}. */
    private Appender<ILoggingEvent> defaultAppender;

    // --- Lifecycle ---

    /**
     * Sets the name of the default (fallback) appender. This name must match the {@code name} attribute
     * of one of the {@code <appender-ref>} elements attached to this appender.
     *
     * @param name the name of the default appender
     */
    public void setDefaultAppender(String name) {
        this.defaultAppenderName = name;
    }

    /**
     * Returns the configured name of the default appender.
     *
     * @return the default appender name, or {@code null} if not yet configured
     */
    public String getDefaultAppender() {
        return defaultAppenderName;
    }

    /**
     * Validates configuration and resolves the default appender reference.
     *
     * <p>This appender will not start if any of the following are true:
     * <ul>
     *   <li>No child appenders are attached</li>
     *   <li>{@code defaultAppender} is not set</li>
     *   <li>The named default appender is not found among the attached appenders</li>
     *   <li>Any attached appender or the resolved default is {@code this} (self-recursion)</li>
     * </ul>
     */
    @Override
    public void start() {
        if (!aai.iteratorForAppenders().hasNext()) {
            addError("MarkerAwareAppender requires at least one appender-ref");
            return;
        }
        if (defaultAppenderName == null || defaultAppenderName.isEmpty()) {
            addError("MarkerAwareAppender requires <defaultAppender> to be set");
            return;
        }
        defaultAppender = aai.getAppender(defaultAppenderName);
        if (defaultAppender == null) {
            addError("Default appender '" + defaultAppenderName + "' not found among attached appenders");
            return;
        }
        if (defaultAppender == this) {
            addError("Default appender must not refer to this appender (self-recursion)");
            return;
        }
        Iterator<Appender<ILoggingEvent>> it = aai.iteratorForAppenders();
        while (it.hasNext()) {
            if (it.next() == this) {
                addError("MarkerAwareAppender must not contain itself as an appender-ref (self-recursion)");
                return;
            }
        }
        super.start();
    }

    /**
     * Stops this appender and detaches all attached child appenders.
     */
    @Override
    public void stop() {
        super.stop();
        aai.detachAndStopAllAppenders();
    }

    // --- Routing ---

    /**
     * Routes the event to the child appender whose name matches the event's marker (or marker hierarchy),
     * or to the default appender if no match is found.
     *
     * @param event the log event to route
     */
    @Override
    protected void append(ILoggingEvent event) {
        Appender<ILoggingEvent> target = resolveAppender(event);
        target.doAppend(event);
    }

    /**
     * Resolves the target appender for the given event by inspecting its marker list.
     *
     * @param event the event whose markers determine the routing target
     * @return the resolved appender; never {@code null} (falls back to {@link #defaultAppender})
     */
    private Appender<ILoggingEvent> resolveAppender(ILoggingEvent event) {
        List<Marker> markers = event.getMarkerList();
        if (markers == null || markers.isEmpty()) {
            return defaultAppender;
        }
        for (Marker marker : markers) {
            // Fast path: direct name match against attached appender names
            Appender<ILoggingEvent> appender = aai.getAppender(marker.getName());
            if (appender != null) {
                return appender;
            }
            // Recursive hierarchy: check if any attached appender name is contained in the marker tree
            if (marker.hasReferences()) {
                Iterator<Appender<ILoggingEvent>> it = aai.iteratorForAppenders();
                while (it.hasNext()) {
                    Appender<ILoggingEvent> candidate = it.next();
                    if (marker.contains(candidate.getName())) {
                        return candidate;
                    }
                }
            }
        }
        return defaultAppender;
    }

    // --- AppenderAttachable delegation ---

    /**
     * Attaches a child appender to this router.
     *
     * @param newAppender the appender to attach; must not be {@code null}
     */
    @Override
    public void addAppender(Appender<ILoggingEvent> newAppender) {
        aai.addAppender(newAppender);
    }

    /**
     * Returns an iterator over all attached child appenders.
     *
     * @return iterator over attached appenders; never {@code null}
     */
    @Override
    public Iterator<Appender<ILoggingEvent>> iteratorForAppenders() {
        return aai.iteratorForAppenders();
    }

    /**
     * Returns the attached child appender with the given name.
     *
     * @param name the appender name to look up
     * @return the matching appender, or {@code null} if not found
     */
    @Override
    public Appender<ILoggingEvent> getAppender(String name) {
        return aai.getAppender(name);
    }

    /**
     * Returns {@code true} if the given appender is attached to this router.
     *
     * @param appender the appender to check
     * @return {@code true} if attached, {@code false} otherwise
     */
    @Override
    public boolean isAttached(Appender<ILoggingEvent> appender) {
        return aai.isAttached(appender);
    }

    /**
     * Detaches and stops all child appenders.
     */
    @Override
    public void detachAndStopAllAppenders() {
        aai.detachAndStopAllAppenders();
    }

    /**
     * Detaches the given appender from this router.
     *
     * @param appender the appender to detach
     * @return {@code true} if the appender was found and removed, {@code false} otherwise
     */
    @Override
    public boolean detachAppender(Appender<ILoggingEvent> appender) {
        return aai.detachAppender(appender);
    }

    /**
     * Detaches the appender with the given name from this router.
     *
     * @param name the name of the appender to detach
     * @return {@code true} if an appender with that name was found and removed, {@code false} otherwise
     */
    @Override
    public boolean detachAppender(String name) {
        return aai.detachAppender(name);
    }
}
