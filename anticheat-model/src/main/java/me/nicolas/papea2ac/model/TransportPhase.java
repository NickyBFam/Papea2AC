package me.nicolas.papea2ac.model;
import java.util.Set;
/** Pre-player connection lifecycle. Only the listed transitions are legal; anything else is rejected. */
public enum TransportPhase {
    ACCEPTED, HANDSHAKING, LOGIN, CONFIGURATION, PLAY, CLOSING, CLOSED;
    private static final Set<TransportPhase> TERMINAL = Set.of(CLOSED);
    public boolean canAdvanceTo(TransportPhase next) {
        if (TERMINAL.contains(this)) return false;
        if (this == CLOSING) return next == CLOSED;
        return switch (this) {
            case ACCEPTED -> next == HANDSHAKING || next == CLOSING;
            case HANDSHAKING -> next == LOGIN || next == CLOSING;
            case LOGIN -> next == CONFIGURATION || next == CLOSING;
            case CONFIGURATION -> next == PLAY || next == CLOSING;
            case PLAY -> next == CLOSING;
            default -> false;
        };
    }
}
