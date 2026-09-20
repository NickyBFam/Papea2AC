package me.nicolas.papea2ac.physics;
import java.util.ArrayList;
import java.util.List;

/** Bounded, deduplicated reason collector for one step; the status is the most severe code seen (MOVEMENT_SIMULATION.md: "Typed reason and affected range"). */
final class Uncertainty {
    static final int MAX_REASONS = 32;
    private final List<PhysicsResult.Reason> reasons = new ArrayList<>();
    private PhysicsResult.Status worst = PhysicsResult.Status.EXACT;
    private int dropped;

    void add(PhysicsResult.Reason.Code code, String detail) {
        if (code.status().ordinal() < worst.ordinal()) worst = code.status();
        for (PhysicsResult.Reason r : reasons) if (r.code() == code && r.detail().equals(detail)) return;
        if (reasons.size() >= MAX_REASONS) { dropped++; return; }
        reasons.add(new PhysicsResult.Reason(code, detail));
    }
    boolean has(PhysicsResult.Reason.Code code) { for (PhysicsResult.Reason r : reasons) if (r.code() == code) return true; return false; }
    PhysicsResult.Status status() { return worst; }
    List<PhysicsResult.Reason> reasons() {
        if (dropped == 0) return List.copyOf(reasons);
        List<PhysicsResult.Reason> out = new ArrayList<>(reasons); out.set(MAX_REASONS - 1, new PhysicsResult.Reason(out.get(MAX_REASONS - 1).code(), out.get(MAX_REASONS - 1).detail() + " (+" + dropped + " more reasons dropped)")); return List.copyOf(out);
    }
    boolean exact() { return worst == PhysicsResult.Status.EXACT; }
}
