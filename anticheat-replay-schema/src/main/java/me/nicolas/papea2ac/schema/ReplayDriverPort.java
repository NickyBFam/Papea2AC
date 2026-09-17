package me.nicolas.papea2ac.schema;

import java.io.IOException;
import java.util.List;

/** Implemented by core; replay storage has no dependency on live reducers. */
public interface ReplayDriverPort {
    List<String> replay(TransportCapsule capsule) throws IOException;
}
