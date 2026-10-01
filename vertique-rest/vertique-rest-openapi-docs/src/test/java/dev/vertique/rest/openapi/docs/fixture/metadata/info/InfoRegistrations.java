// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.info;

import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import java.util.List;

/**
 * The application names and paths of the {@code info} fixture interfaces, and their hand-written
 * registrations. Each registration is one {@link GeneratedRestApplicationRegistration#of} call
 * exactly as the annotation processor would emit it, since the processor does not run on framework
 * test sources: active, listing {@link InfoPingResource}, with the global contract location.
 *
 * <p>Two applications are used. {@value #PUBLIC_NAME} at {@value #PUBLIC_PATH} is the one a deployed
 * composition serves; {@value #CHILD_NAME} at {@value #CHILD_PATH} is the one whose configuration is
 * resolved without deploying. A fixture interface's own {@code @RestApplication} names the same
 * application its registration uses.
 */
public final class InfoRegistrations {

    /** The name of the served application, which also names its document. */
    public static final String PUBLIC_NAME = "public";

    /** The path of the served application. */
    public static final String PUBLIC_PATH = "/api/public";

    /** The name of the application whose configuration is resolved without deploying. */
    public static final String CHILD_NAME = "child";

    /** The path of the application whose configuration is resolved without deploying. */
    public static final String CHILD_PATH = "/api/child";

    private InfoRegistrations() {}

    /**
     * Registers application {@value #PUBLIC_NAME} at {@value #PUBLIC_PATH}, declared by the given
     * interface.
     *
     * @param declaringType the declaring interface, for example {@link FullInfoApi} or {@link
     *     BasicInfoApi}
     * @return the registration
     */
    public static GeneratedRestApplicationRegistration publicApi(Class<?> declaringType) {
        return register(declaringType, PUBLIC_NAME, PUBLIC_PATH);
    }

    /**
     * Registers application {@value #CHILD_NAME} at {@value #CHILD_PATH}, declared by the given
     * interface.
     *
     * @param declaringType the declaring interface, for example {@link ChildInfoApi}
     * @return the registration
     */
    public static GeneratedRestApplicationRegistration childApi(Class<?> declaringType) {
        return register(declaringType, CHILD_NAME, CHILD_PATH);
    }

    private static GeneratedRestApplicationRegistration register(Class<?> declaringType, String name, String path) {
        return GeneratedRestApplicationRegistration.of(
                declaringType, name, path, List.of(InfoPingResource.class), false, "", true);
    }
}
