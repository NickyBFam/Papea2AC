package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import me.nicolas.papea2ac.model.TransportPhase;
import me.nicolas.papea2ac.model.TransportSessionId;

class TransportPhaseTest {
    @Test void onlyTheDocumentedForwardTransitionsAreLegal(){
        assertTrue(TransportPhase.ACCEPTED.canAdvanceTo(TransportPhase.HANDSHAKING));
        assertTrue(TransportPhase.HANDSHAKING.canAdvanceTo(TransportPhase.LOGIN));
        assertTrue(TransportPhase.LOGIN.canAdvanceTo(TransportPhase.CONFIGURATION));
        assertTrue(TransportPhase.CONFIGURATION.canAdvanceTo(TransportPhase.PLAY));
        for(TransportPhase phase:TransportPhase.values())assertTrue(phase.canAdvanceTo(TransportPhase.CLOSING)||phase==TransportPhase.CLOSING||phase==TransportPhase.CLOSED);
    }
    @Test void regressionsAndSkipsAreRejected(){
        assertFalse(TransportPhase.PLAY.canAdvanceTo(TransportPhase.LOGIN));
        assertFalse(TransportPhase.CLOSED.canAdvanceTo(TransportPhase.CONFIGURATION));
        assertFalse(TransportPhase.ACCEPTED.canAdvanceTo(TransportPhase.CONFIGURATION));
        assertFalse(TransportPhase.HANDSHAKING.canAdvanceTo(TransportPhase.PLAY));
        assertFalse(TransportPhase.CLOSING.canAdvanceTo(TransportPhase.PLAY));
    }
    @Test void closedIsTerminal(){
        for(TransportPhase target:TransportPhase.values())assertFalse(TransportPhase.CLOSED.canAdvanceTo(target));
    }
    @Test void transportSessionIdIsNeverClientDerivable(){
        assertThrows(IllegalArgumentException.class,()->new TransportSessionId(0));
        assertThrows(IllegalArgumentException.class,()->new TransportSessionId(-1));
        assertNotEquals(new TransportSessionId(1),new TransportSessionId(2));
    }
}
