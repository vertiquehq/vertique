// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb.membership;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * TP-005 case 16's wrong-type application: an unrelated {@link Application} subtype (not a subtype
 * of {@link MembershipDeclaredApplication}) that
 * {@link MembershipMismatchedFactoryRegistrationModule}'s factory constructs and returns for a
 * registration declared as {@link MembershipDeclaredApplication}, tripping C-COMPOSE step 4's type
 * check. Never constructed through its own registration, so it carries no counters.
 */
@ApplicationPath("/api/wrong-type")
public class MembershipWrongTypeApplication extends Application {

    /** Public no-arg constructor. */
    public MembershipWrongTypeApplication() {}
}
