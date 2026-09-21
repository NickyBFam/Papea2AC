package me.nicolas.papea2AC.platform;
import io.netty.channel.Channel;
import me.nicolas.papea2ac.core.MarkerPort;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.lang.reflect.Constructor;
import java.util.UUID;
/**
 * Phase 7 marker port: writes the pinned protocol's {@code ClientboundPingPacket(int)} on the player's channel. The write enters
 * the pipeline at the tail and passes Papea2AC's own outbound capture (an Outbound PING observation with a wire ordinal, then a
 * WriteOutcome), so a marker is evidence exactly like every other outbound packet; the client's pong is captured inbound. The
 * write is queued on the channel's event loop in submission order (THREADING_MODEL.md: relative wire placement is an ordered
 * event-loop operation), so a marker issued after an observed influence follows it on the wire. Reflection against the pinned
 * class only; an unavailable class disables markers (keep-alive evidence remains), never the plugin.
 */
public final class PaperMarkerPort implements MarkerPort {
    private final Constructor<?> ping;
    public PaperMarkerPort() {
        Constructor<?> c = null;
        try { c = Class.forName("net.minecraft.network.protocol.common.ClientboundPingPacket").getConstructor(int.class); } catch (ReflectiveOperationException | RuntimeException ignored) { /* markers unavailable */ }
        ping = c;
    }
    public boolean available() { return ping != null; }
    @Override public boolean sendMarker(UUID player, int id) {
        if (ping == null) return false;
        Player p = Bukkit.getPlayer(player);
        if (p == null || !p.isOnline()) return false;
        try {
            Channel channel = PaperCapture.channel(p);
            if (channel == null || !channel.isActive()) return false;
            channel.writeAndFlush(ping.newInstance(id));
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }
}
