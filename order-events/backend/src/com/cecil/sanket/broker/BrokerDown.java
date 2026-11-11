package com.cecil.sanket.broker;

/** Thrown while the broker is switched off (a fault from the simulator): nothing can be written or read. */
public class BrokerDown extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public BrokerDown() { super("The event broker is not reachable (connection refused)."); }
}
