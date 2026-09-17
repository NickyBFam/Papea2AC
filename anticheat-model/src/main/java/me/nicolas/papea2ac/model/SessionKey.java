package me.nicolas.papea2ac.model;
import java.util.UUID;
import java.util.Objects;
public record SessionKey(UUID player, long epoch) {
    public SessionKey { Objects.requireNonNull(player); if (epoch < 1) throw new IllegalArgumentException("epoch"); }
}
