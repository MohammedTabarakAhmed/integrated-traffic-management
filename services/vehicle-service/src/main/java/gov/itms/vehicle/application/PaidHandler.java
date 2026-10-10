package gov.itms.vehicle.application;

import gov.itms.platform.security.CurrentActor;
import gov.itms.vehicle.domain.Registration;
import java.time.Instant;

/** Completes a paid transaction of the kinds it {@link #handles} once payment-service confirms the payment. */
public interface PaidHandler {

    boolean handles(Registration.Kind kind);

    void completed(Registration.Request request, Instant now, CurrentActor system);
}
