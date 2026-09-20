package me.nicolas.papea2AC;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.entity.Player;
import io.netty.channel.Channel;
import java.util.*;
import java.util.logging.Level;
import me.nicolas.papea2ac.core.PacketEngine;
import me.nicolas.papea2ac.core.CoverageService;
import me.nicolas.papea2ac.core.EngineConfiguration;
import me.nicolas.papea2ac.api.PapeaService;
import org.bukkit.plugin.ServicePriority;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import java.nio.file.Files;
import java.io.IOException;
import me.nicolas.papea2ac.core.PacketMetrics;
import me.nicolas.papea2AC.platform.PaperCapture;
/** Phase 1 development plugin; no enforcement or qualified outcome provider. */
public final class Papea2AC extends JavaPlugin implements Listener {
    private record Binding(Channel channel,PacketEngine.Session session){}
    private final Map<UUID,Binding> bindings=new HashMap<>();
    private PacketEngine engine;private PaperCapture capture;
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    @Override public void onEnable(){
        if(!getServer().getMinecraftVersion().equals("26.2")){
            getLogger().severe("Unsupported development profile: requires Paper 26.2. Capture disabled.");
            getServer().getPluginManager().disablePlugin(this);return;
        }
        try{
            var inventory=me.nicolas.papea2AC.platform.PaperPacketInventory.inspect();
            me.nicolas.papea2AC.platform.SemanticCoverage.verify(inventory);
            if(!me.nicolas.papea2AC.platform.PaperPacketInventory.matchesPinned(inventory)){
                getLogger().severe("Native packet inventory fingerprint mismatch; capture disabled.");
                getServer().getPluginManager().disablePlugin(this);return;
            }
        }catch(ReflectiveOperationException|RuntimeException|IOException e){
            getLogger().log(Level.SEVERE,"Native inventory probe unavailable; capture disabled",e);
            getServer().getPluginManager().disablePlugin(this);return;
        }
        try{
            var path=getDataFolder().toPath().resolve("engine.properties");
            String text=Files.exists(path)?readConfiguration(path):"";
            EngineConfiguration config=EngineConfiguration.parse(text,Runtime.getRuntime().maxMemory());
            if(config.mode()==me.nicolas.papea2ac.core.RuntimeControl.Mode.DISABLED){getServer().getPluginManager().disablePlugin(this);return;}
            engine=new PacketEngine(config.workers(),config.capacity(),config.totalBytes(),e->failure.compareAndSet(null,e));
            engine.enableRecording(getDataFolder().toPath().resolve("transport-captures"),config.digest());
        }catch(IllegalArgumentException|IOException e){getLogger().log(Level.SEVERE,"Invalid AC configuration",e);getServer().getPluginManager().disablePlugin(this);return;}
        capture=new PaperCapture(engine);
        if(!capture.registerEarlyHook())getLogger().warning("Early transport capture unavailable on this server; falling back to join-time attachment only.");
        getServer().getServicesManager().register(PapeaService.class,new CoverageService(engine),this,ServicePriority.Normal);
        getServer().getScheduler().runTaskTimer(this,()->{
            engine.supervise();bindings.values().forEach(binding->capture.audit(binding.session()));Throwable error=failure.getAndSet(null);
            if(error!=null)getLogger().log(Level.SEVERE,"Packet actor quarantined; inspect coverage",error);
        },20,20);
        getServer().getScheduler().runTaskTimer(this,()->{
            long tick=getServer().getCurrentTick();
            for(Binding binding:bindings.values())binding.session().ingress.bridge(new me.nicolas.papea2ac.protocol.Observation.Tick(tick));
        },1,1);
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getOnlinePlayers().forEach(this::attach);
        getLogger().warning("DEVELOPMENT OBSERVE_ONLY: authoritative outcomes and complete packet inventory are unqualified.");
    }
    private void attach(Player player){
        if(!engine.captureEnabled())return;
        try{
            Binding old=bindings.remove(player.getUniqueId());if(old!=null)capture.detach(old.channel(),old.session());
            Channel channel=PaperCapture.channel(player);bindings.put(player.getUniqueId(),new Binding(channel,capture.bind(player.getUniqueId(),channel)));
        }
        catch(ReflectiveOperationException|RuntimeException e){getLogger().log(Level.WARNING,"Capture unavailable: adapter boundary mismatch",e);}
    }
    private static String readConfiguration(java.nio.file.Path path)throws IOException{
        if(Files.size(path)>16384)throw new IOException("configuration exceeds 16 KiB");
        return Files.readString(path);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!sender.hasPermission("papea2ac.admin")){sender.sendMessage("Permission denied.");return true;}
        if(engine==null){sender.sendMessage("Papea2AC is not admitted.");return true;}
        if(args.length==2&&args[0].equalsIgnoreCase("capture")){
            if(!args[1].equalsIgnoreCase("on")&&!args[1].equalsIgnoreCase("off")){sender.sendMessage("Usage: /papea2ac capture on|off");return true;}
            boolean enabled=args[1].equalsIgnoreCase("on");
            if(enabled!=engine.captureEnabled()){
                engine.setCaptureEnabled(enabled);
                if(enabled)getServer().getOnlinePlayers().forEach(this::attach);
                else{for(Binding b:bindings.values())capture.detach(b.channel(),b.session());bindings.clear();}
            }
        }else if(args.length==2&&args[0].equalsIgnoreCase("effects")&&args[1].equalsIgnoreCase("suspend"))engine.suspendEffects();
        else if(args.length==2&&args[0].equalsIgnoreCase("effects")&&args[1].equalsIgnoreCase("resume"))engine.resumeEffects();
        else if(args.length==3&&args[0].equalsIgnoreCase("recording")&&(args[2].equalsIgnoreCase("on")||args[2].equalsIgnoreCase("off"))){
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            boolean accepted=engine.setRecordingEnabled(player.getUniqueId(),args[2].equalsIgnoreCase("on"));
            sender.sendMessage(accepted?"Recording control accepted; applies at this session's next ordered position.":"Recording control rejected (no writer configured, or session closed).");
            return true;
        }else if(args.length==2&&args[0].equalsIgnoreCase("checkpoint")){
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            sender.sendMessage(engine.requestReplay(player.getUniqueId())?"Checkpoint/export requested; applies at this session's next ordered position.":"Checkpoint request rejected (no writer configured, or session closed).");
            return true;
        }else if(args.length==2&&args[0].equalsIgnoreCase("player")){
            Player player=getServer().getPlayerExact(args[1]);
            sender.sendMessage(player==null?"Player is not online.":engine.coverage(player.getUniqueId()).toString());return true;
        }else if(args.length==2&&args[0].equalsIgnoreCase("timeline")){
            // Bounded summary of the published Timeline snapshot only - never a history dump. Every value is an
            // OBSERVED fact (claimed position, sent teleport, confirm packet), not validated truth.
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            var status=engine.timelineStatus(player.getUniqueId());
            if(status.isEmpty()){sender.sendMessage("No admitted session for that player.");return true;}
            var t=status.get();
            sender.sendMessage("timeline sessionEpoch="+t.sessionEpoch()+" lastSequence="+t.lastCommittedSequence()+" retained="+t.retainedRecords()
                +" chargedBytes="+t.retainedChargedBytes()+" evictions="+t.evictions()+" worldEpoch="+t.worldEpoch()+" revision="+t.revision()
                +" coverageIssue="+t.lastAppendHadCoverageIssue());
            sender.sendMessage("observed position="+(t.hasPosition()?t.x()+","+t.y()+","+t.z():"none")+" rotation="+(t.hasRotation()?t.yaw()+"/"+t.pitch():"none")
                +" onGround="+t.onGround()+" serverTick="+(t.hasServerTick()?t.lastServerTick():"none"));
            sender.sendMessage("teleportSent="+t.teleportSent()+(t.teleportSent()?" id="+t.teleportId()+" confirmPacketSeen="+t.teleportAcknowledged():"")
                +" activeExternalMotion="+t.activeExternalMotionInfluences()+" externalMotionOverflow="+t.externalMotionOverflow());
            return true;
        }else if(args.length==2&&args[0].equalsIgnoreCase("world")){
            // Bounded summary of the published observer-world snapshot - never a chunk/block dump. Everything shown is
            // what was OBSERVED as sent to this client, not server world truth.
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            var status=engine.worldStatus(player.getUniqueId());
            if(status.isEmpty()){sender.sendMessage("No admitted session for that player.");return true;}
            var w=status.get();
            sender.sendMessage("world sessionEpoch="+w.sessionEpoch()+" worldEpoch="+w.worldEpoch()+" dimension="+(w.hasDimension()?w.dimensionId()+" y["+w.minY()+".."+(w.minY()+w.height()-1)+"]":"UNKNOWN")
                +" revision="+w.revision()+" lastSequence="+w.lastSequence());
            sender.sendMessage("chunks="+w.chunkCount()+" complete="+w.completeChunks()+" partial="+w.partialChunks()+" contentNotRetained="+w.contentNotRetainedChunks()
                +" transportIncomplete="+w.transportIncompleteChunks()+" evicted="+w.evictedChunks()+" unloaded="+w.unloadedChunks());
            sender.sendMessage("privateBytes="+w.privateBytes()+" historyBytes="+w.historyBytes()+" historyEntries="+w.historyEntries()+" historyEvictions="+w.historyEvictions()
                +" coverage="+(w.transportBroken()?"BREAKS="+w.transportBreaks()+" last="+w.transportBreakReason()+"@"+w.lastBreakSequence()+" (held chunks incomplete until re-baselined)":"HEALTHY"));
            sender.sendMessage("blockUpdates="+w.blockUpdates()+" last="+(w.lastBlockUpdate().isEmpty()?"none":w.lastBlockUpdate()));
            for(int i=0;i<w.recentBlockUpdates().size();i++)sender.sendMessage("  recent["+i+"] "+w.recentBlockUpdates().get(i));// bounded: at most 8 lines
            return true;
        }else if(args.length==2&&args[0].equalsIgnoreCase("collision")){
            // Bounded geometry readout (Phase 4 §54): the observed player box (two explicit poses) against the PUBLISHED observer-world snapshot -
            // certainty, coverage counts and at most 8 boxes per pose. Geometry only: nothing here judges whether a movement was valid.
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            var status=engine.collisionStatus(player.getUniqueId());
            if(status.isEmpty()){sender.sendMessage("No admitted session for that player.");return true;}
            var c=status.get();
            sender.sendMessage("collision sessionEpoch="+c.sessionEpoch()+" worldEpoch="+c.worldEpoch()+" revision="+c.worldRevision()+" throughSequence="+c.worldSequence()+" profile="+c.profile()
                +" observedPosition="+(c.hasPosition()?c.x()+","+c.y()+","+c.z():"none"));
            for(var p:c.poses()){
                sender.sendMessage(p.pose()+" box=["+p.minX()+","+p.minY()+","+p.minZ()+" -> "+p.maxX()+","+p.maxY()+","+p.maxZ()+"] certainty="+p.certainty()+(p.unavailableReason().isEmpty()?"":"("+p.unavailableReason()+")")
                    +" cells="+p.cellsVisited()+" hits="+p.hits()+" boxes="+p.boxes()+(p.truncated()?" TRUNCATED":"")+" definitelyObstructed="+p.definitelyObstructed()+" definitelyClear="+p.definitelyClear());
                sender.sendMessage("  cells: "+(p.statusCounts().isEmpty()?"none":p.statusCounts()));
                for(int i=0;i<p.firstBoxes().size();i++)sender.sendMessage("  box["+i+"] "+p.firstBoxes().get(i));// bounded: at most 8 lines per pose
            }
            return true;
        }else if(args.length>0&&!args[0].equalsIgnoreCase("status")){
            sender.sendMessage("Usage: /papea2ac [status | player <name> | timeline <name> | world <name> | collision <name> | capture on|off | effects suspend|resume | recording <name> on|off | checkpoint <name>]");return true;
        }
        sender.sendMessage("capture="+engine.captureEnabled()+" effectsSuspended="+engine.effectsSuspended()+" correlation=UNSUPPORTED");
        sender.sendMessage("Papea2AC OBSERVE_ONLY sessions="+engine.sessions()+" reservedBytes="+engine.reservedBytes());
        for(PacketMetrics.Kind kind:PacketMetrics.Kind.values())sender.sendMessage(kind.name()+"="+engine.metrics.count(kind));
        sender.sendMessage("replay "+engine.replayStatus());
        sender.sendMessage("delaySamples="+engine.metrics.delaySamples()+" totalDelayNanos="+engine.metrics.totalDelayNanos());
        sender.sendMessage("delayP95UpperNanos="+engine.metrics.delayPercentileUpperBound(.95)+" delayP99UpperNanos="+engine.metrics.delayPercentileUpperBound(.99));
        var monitoring=engine.monitoringSnapshot();
        sender.sendMessage("monitoring activeSessions="+monitoring.activeSessions()+" certifiedSessions="+monitoring.certifiedSessions()
            +" overloadedSessions="+monitoring.overloadedSessions()+" queueOccupancy="+monitoring.queueOccupancyTotal()+"/"+monitoring.queueCapacityTotal()
            +" writerHealth="+monitoring.writerHealth()+" checkpointsCreated="+monitoring.checkpointsCreated()+" recordingPauses="+monitoring.recordingPauses());
        var budget=engine.budgetStatus();
        sender.sendMessage("budget rollingBytes="+budget.replayRollingBytesUsed()+"/"+budget.replayRollingBytesLimit()
            +" promotionBytes="+budget.replayPromotionBytesUsed()+" ioBytes="+budget.replayIoBytesUsed()+" writerQueueCapacity="+budget.writerQueueCapacity()
            +" infrastructurePool="+engine.infrastructurePool.used()+"/"+engine.infrastructurePool.limit());
        var world=engine.worldBudgetSnapshot();
        sender.sendMessage("world observers="+world.observerWorlds()+" chunks="+world.chunks()+" complete="+world.completeChunks()+" partial="+world.partialChunks()+" sections="+world.sections()
            +" sharedBytes="+world.sharedBytes()+"/"+world.sharedLimit()+" sharedSections="+world.sharedSections()+" sharedHits="+world.sharedHits()+" privateTier="+world.privateLimit()
            +" revisions="+world.revisions()+" historyEvictions="+world.historyEvictions()+" evicted="+world.evictedChunks()+" unloaded="+world.unloadedChunks()+" coverageBreaks="+world.coverageBreaks()+" budgetRejections="+world.budgetRejections());
        var col=engine.collisionEngineStatus();
        sender.sendMessage("collision profile="+col.profile()+" shapeTable="+col.tableStates()+"states/"+col.tableShapes()+"shapes/"+col.tableBlocks()+"blocks sha256="+col.tableDigest().substring(0,16)+" queries="+col.queries()+" complete="+col.complete()+" incomplete="+col.incomplete()
            +" unavailable="+col.unavailable()+" rejected="+col.rejected()+" truncated="+col.truncated()+" cells="+col.cellsVisited()+" hits="+col.hits()+" cacheHits="+col.shapeCacheHits()+" cacheMisses="+col.shapeCacheMisses()
            +" unsupportedStates="+col.unsupportedStates()+" contextRequired="+col.contextRequired()+" dynamicCells="+col.dynamicCells()+" worldUnknownCells="+col.worldUnknownCells());
        return true;
    }
    @EventHandler public void join(PlayerJoinEvent event){attach(event.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent event){Binding b=bindings.remove(event.getPlayer().getUniqueId());if(b!=null)capture.detach(b.channel(),b.session());}
    @Override public void onDisable(){
        HandlerList.unregisterAll((Listener)this);
        getServer().getServicesManager().unregisterAll(this);
        if(capture!=null){capture.unregisterEarlyHook();for(Binding b:bindings.values())capture.detach(b.channel(),b.session());}
        bindings.clear();
        if(engine!=null){engine.close();if(!engine.awaitTermination(2000))getLogger().warning("Papea2AC shards did not drain within 2 s of disable; sessions were force-fenced.");}// drain before Bukkit closes the plugin jar (P3-F13)
    }
}
