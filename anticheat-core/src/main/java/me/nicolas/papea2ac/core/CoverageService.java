package me.nicolas.papea2ac.core;
import me.nicolas.papea2ac.api.*;
public final class CoverageService implements PapeaService {
    private final PacketEngine engine;
    public CoverageService(PacketEngine engine){this.engine=engine;}
    @Override public CoverageSummary coverage(PublicPlayerId id){return engine.coverage(id.value());}
}
