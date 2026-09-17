package me.nicolas.papea2ac.packet;
/** Probe output: capture, authoritative outcome coverage and integrity are independent. */
public record TransportCapability(boolean capture, boolean outcomes, boolean mediatedIntegrity, boolean baselineReconciled) {
    public static TransportCapability observeOnly() {return new TransportCapability(true,false,false,false);}
    public boolean certification() {return capture && outcomes && mediatedIntegrity && baselineReconciled;}
}
