package com.cecil.kaushal.model;

/**
 * CLAIMED  = the person says "I am at level 3" - waits for someone to check it.
 * VERIFIED = a manager or an assessor checked it (maybe at a different level).
 * RETURNED = sent back with a question ("add an example / evidence").
 */
public enum ClaimStatus { CLAIMED, VERIFIED, RETURNED }
