package gov.itms.hold.domain;

/** Hold lifecycle (LLD §2.2). Only ACTIVE and LIFTED are stored; EXPIRED is derived from the end date. */
public enum HoldStatus { ACTIVE, LIFTED, EXPIRED }
