package gov.itms.appointment.domain;

/** Channels a request comes through (API_SPECIFICATION §2, {@code X-Channel}); recorded on every change. */
public enum Channel { PORTAL, APP, BACKOFFICE, PARTNER, KIOSK, CALLCENTRE, FIELD, ASSISTANT, AGENT }
