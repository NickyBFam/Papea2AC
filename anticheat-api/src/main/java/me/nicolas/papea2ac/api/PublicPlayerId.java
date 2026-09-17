package me.nicolas.papea2ac.api;
import java.util.UUID;
import java.util.Objects;
public record PublicPlayerId(UUID value) {public PublicPlayerId{Objects.requireNonNull(value);}}
