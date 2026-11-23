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
    /** Phase 10: the one main-thread executor that may apply a mitigation intent (effects stay off unless the runtime mode is ENFORCE). */
    private me.nicolas.papea2AC.platform.PaperMitigationExecutor mitigation;
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
            engine.enableDecisionCapsules(getDataFolder().toPath().resolve("decision-capsules"),config.digest()); // Phase 10: durable decision capsules (DURABLE_REQUIRED)
            configuredCapacity=config.capacity();
        }catch(IllegalArgumentException|IOException e){getLogger().log(Level.SEVERE,"Invalid AC configuration",e);getServer().getPluginManager().disablePlugin(this);return;}
        capture=new PaperCapture(engine);
        mitigation=new me.nicolas.papea2AC.platform.PaperMitigationExecutor(engine,me.nicolas.papea2ac.mitigation.MitigationBounds.DEFAULT);
        // Phase 7 marker port: the pinned protocol's clientbound ping written on the player's channel (captured outbound like any packet); a missing class disables markers, never the plugin.
        engine.setMarkerPort(new me.nicolas.papea2AC.platform.PaperMarkerPort());
        engine.setValidationInvalidSink(text->getLogger().info(text)); // Phase 8 diagnostic (P8-F7): every live INVALID is logged in full; display only, never enforcement
        if(!capture.registerEarlyHook())getLogger().warning("Early transport capture unavailable on this server; falling back to join-time attachment only.");
        getServer().getServicesManager().register(PapeaService.class,new CoverageService(engine),this,ServicePriority.Normal);
        getServer().getScheduler().runTaskTimer(this,()->{
            engine.supervise();bindings.values().forEach(binding->capture.audit(binding.session()));Throwable error=failure.getAndSet(null);
            if(error!=null)getLogger().log(Level.SEVERE,"Packet actor quarantined; inspect coverage",error);
        },20,20);
        getServer().getScheduler().runTaskTimer(this,()->{
            long tick=getServer().getCurrentTick();
            for(Binding binding:bindings.values())binding.session().ingress.bridge(new me.nicolas.papea2ac.protocol.Observation.Tick(tick));
            mitigation.tick(getServer()); // Phase 10: queued intents, re-checked and applied (or rejected) here only
        },1,1);
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getOnlinePlayers().forEach(this::attach);
        getLogger().warning("DEVELOPMENT OBSERVE_ONLY: authoritative outcomes and complete packet inventory are unqualified.");
    }
    private void attach(Player player){
        if(!engine.captureEnabled())return;
        try{
            Binding old=bindings.remove(player.getUniqueId());if(old!=null)capture.detach(old.channel(),old.session());
            Channel channel=PaperCapture.channel(player);Binding binding=new Binding(channel,capture.bind(player.getUniqueId(),channel));bindings.put(player.getUniqueId(),binding);
            // Phase 7: the player's own entity id, sequenced through the ingress like every administrative fact (velocity attribution, P7-F1)
            binding.session().ingress.bridge(new me.nicolas.papea2ac.protocol.Observation.ControlChange("SELF_ENTITY",Integer.toString(player.getEntityId())));
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
        else if(args.length==2&&args[0].equalsIgnoreCase("effects")&&args[1].equalsIgnoreCase("enforce-qualification")){
            // Phase 10 Decision 3: temporary ENFORCE for the controlled test-server qualification ONLY, and only after TC25 passed.
            // Requires the JVM flag -Dpapea2ac.qualificationEnforce=true (never set in production) AND tc25-result.properties with pass=true.
            boolean flag=Boolean.getBoolean("papea2ac.qualificationEnforce");boolean tc25=tc25Passed();
            if(!flag||!tc25){sender.sendMessage("REFUSED: qualification ENFORCE needs -Dpapea2ac.qualificationEnforce=true ("+flag+") and a passing TC25 result ("+tc25+"); mode stays "+(engine.effectsSuspended()?"not ENFORCE":"ENFORCE"));return true;}
            try{engine.enableEnforce(true,true);sender.sendMessage("ENFORCE enabled for controlled qualification (G2 guards active; /papea2ac effects resume returns to OBSERVE_ONLY)");}catch(IllegalStateException ex){sender.sendMessage("REFUSED: "+ex.getMessage());}
            return true;
        }
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
        }else if(args.length>=2&&args[0].equalsIgnoreCase("physics")){
            // Phase 5 diagnostic + real-client qualification tracer. Inputs are DECLARED by the operator (a scenario), never inferred;
            // the output compares the simulator's prediction under that declared input with the observed positions - a discrepancy is
            // information, not a verdict. Server-current facts (border, effects, boots, attribute bases, nearby entities) are read on the
            // main thread here and passed as primitives with that provenance.
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            String op=args.length>=3?args[2].toLowerCase(Locale.ROOT):"step";
            if(op.equals("report")){for(String line:engine.physicsReport(player.getUniqueId()))sender.sendMessage(line);return true;}
            if(op.equals("cancel")){sender.sendMessage(engine.physicsCancel(player.getUniqueId()));return true;}
            var scenario=me.nicolas.papea2ac.core.PhysicsQualification.Scenario.parse(args.length>=4?args[3]:"release_all");
            if(scenario==null){sender.sendMessage("Unknown scenario; one of "+Arrays.toString(me.nicolas.papea2ac.core.PhysicsQualification.Scenario.values()));return true;}
            var facts=armFacts(player);
            if(op.equals("arm")){sender.sendMessage(engine.physicsArm(player.getUniqueId(),scenario,facts));return true;}
            for(String line:engine.physicsOneStep(player.getUniqueId(),scenario,facts))sender.sendMessage(line);
            return true;
        }else if(args.length>=2&&args[0].equalsIgnoreCase("reconstruct")){
            // Phase 6 diagnostic + real-client qualification tracer: which LEGAL inputs could explain each observed transition. An optional
            // declared scenario is checked for MEMBERSHIP in the candidate set only (never assumed); nothing here is a verdict.
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            String op=args.length>=3?args[2].toLowerCase(Locale.ROOT):"report";
            if(op.equals("report")){for(String line:engine.reconstructReport(player.getUniqueId()))sender.sendMessage(line);return true;}
            if(op.equals("cancel")){sender.sendMessage(engine.reconstructCancel(player.getUniqueId()));return true;}
            if(op.equals("arm")){
                me.nicolas.papea2ac.core.PhysicsQualification.Scenario declared=null;
                if(args.length>=4){declared=me.nicolas.papea2ac.core.PhysicsQualification.Scenario.parse(args[3]);if(declared==null){sender.sendMessage("Unknown scenario; one of "+Arrays.toString(me.nicolas.papea2ac.core.PhysicsQualification.Scenario.values()));return true;}}
                sender.sendMessage(engine.reconstructArm(player.getUniqueId(),declared,armFacts(player)));return true;
            }
            sender.sendMessage("Usage: /papea2ac reconstruct <name> [arm [declared-scenario] | report | cancel]");return true;
        }else if(args.length>=2&&args[0].equalsIgnoreCase("timing")){
            // Phase 7 diagnostic + real-client qualification: WHEN could the client have applied corrections/influences; bounded hypotheses, never a verdict.
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            String op=args.length>=3?args[2].toLowerCase(Locale.ROOT):"view";
            switch(op){
                case "view" -> { var v=engine.timingStatus(player.getUniqueId()); if(v.isEmpty()){sender.sendMessage("no admitted session");return true;} var t=v.get();
                    sender.sendMessage("timing session="+t.sessionEpoch()+" worldEpoch="+t.worldEpoch()+" tickEndCapable="+t.tickEndCapable()+" tickEnds="+t.clientTickEnds()+" movements="+t.movements()+" influences="+t.influences()+" teleports="+t.teleportsSent()+"/"+t.teleportsConfirmed()+" stale="+t.staleTeleportConfirms()+" evictions="+t.evictions());
                    sender.sendMessage("rtt "+(t.rttPresent()?String.format(Locale.ROOT,"last=%.3fms min=%.3fms smoothed=%.3fms jitter=%.3fms max=%.3fms",t.rttLastNanos()/1e6,t.rttMinNanos()/1e6,t.rttSmoothedNanos()/1e6,t.rttJitterNanos()/1e6,t.rttMaxNanos()/1e6)+" samples="+t.rttSamples()+" outliers="+t.rttOutliers()+" rejected="+t.rttRejected()+" age="+t.rttAgeNanos()/1_000_000+"ms":"none")
                        +" | markers sent="+t.markersSent()+" acked="+t.markersAcknowledged()+" pending="+t.markersPending()+" expired="+t.markersExpired()+" late/unknown="+t.markersLateOrUnknown()+" failedWrites="+t.markerFailedWrites());
                    for(String line:t.corrections())sender.sendMessage(line);
                    return true; }
                case "report" -> { for(String line:engine.timingReport(player.getUniqueId()))sender.sendMessage(line); return true; }
                case "cancel" -> { sender.sendMessage(engine.timingCancel(player.getUniqueId())); return true; }
                case "arm" -> {
                    me.nicolas.papea2ac.core.PhysicsQualification.Scenario declared=null;
                    if(args.length>=4){declared=me.nicolas.papea2ac.core.PhysicsQualification.Scenario.parse(args[3]);if(declared==null){sender.sendMessage("Unknown scenario; one of "+Arrays.toString(me.nicolas.papea2ac.core.PhysicsQualification.Scenario.values()));return true;}}
                    sender.sendMessage(engine.timingArm(player.getUniqueId(),declared,armFacts(player)));return true; }
                case "velocity" -> { // controlled test harness (brief section 63): a known server-applied velocity, main thread, captured outbound like any velocity
                    if(args.length<6){sender.sendMessage("Usage: /papea2ac timing <name> velocity <x> <y> <z>");return true;}
                    try{double x=Double.parseDouble(args[3]),y=Double.parseDouble(args[4]),z=Double.parseDouble(args[5]);player.setVelocity(new org.bukkit.util.Vector(x,y,z));sender.sendMessage("velocity ("+x+","+y+","+z+") applied server-side (entity "+player.getEntityId()+"); the packet is captured outbound");}
                    catch(NumberFormatException e){sender.sendMessage("numbers required");}
                    return true; }
                case "teleport" -> { // controlled test harness (brief section 64): a known relative-offset teleport through the Bukkit API (absolute position correction on the wire)
                    if(args.length<6){sender.sendMessage("Usage: /papea2ac timing <name> teleport <dx> <dy> <dz>");return true;}
                    try{double dx=Double.parseDouble(args[3]),dy=Double.parseDouble(args[4]),dz=Double.parseDouble(args[5]);boolean ok=player.teleport(player.getLocation().add(dx,dy,dz));sender.sendMessage("teleport by ("+dx+","+dy+","+dz+") "+(ok?"issued":"refused")+"; the correction is captured outbound");}
                    catch(NumberFormatException e){sender.sendMessage("numbers required");}
                    return true; }
                default -> { sender.sendMessage("Usage: /papea2ac timing <name> [view | arm [declared-scenario] | report | cancel | velocity <x> <y> <z> | teleport <dx> <dy> <dz>]"); return true; }
            }
        }else if(args.length>=2&&args[0].equalsIgnoreCase("mitigation")){
            // Phase 10 diagnostic: eligibility, typed suppression reason, chains, candidate anchor, pending correction, cooldown, last outcome. Never a label or a recommendation.
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            var st=engine.mitigationStatus(player.getUniqueId());if(st.isEmpty()){sender.sendMessage("no admitted session");return true;}
            for(String line:mitigationLines(st.get()))sender.sendMessage(line);
            return true;
        }else if(args.length>=2&&args[0].equalsIgnoreCase("combat")){
            // Phase 11 diagnostic: descriptive combat observations only - session/epoch, entity registry usage and honesty markers, the last attacks with their
            // target network state (incarnation, exactness, pose), attacker timeline provenance, timing status and completeness reasons. Never a verdict,
            // a label, a score or a recommendation; target state is packet-observed only (no live Bukkit entity is read).
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            var lines=engine.combatDiagnosticLines(player.getUniqueId(),args.length>=3&&args[2].equalsIgnoreCase("entities"));if(lines.isEmpty()){sender.sendMessage("no admitted session");return true;}
            for(String line:lines)sender.sendMessage(line);
            return true;
        }else if(args.length>=2&&args[0].equalsIgnoreCase("evidence")){
            // Phase 9 diagnostic: what the completed Phase 8 results prove over the active window (counts, families, continuity, gaps, magnitudes, ages, supporting ids); bounded; never a level, never a recommendation, never an action.
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            String op=args.length>=3?args[2].toLowerCase(Locale.ROOT):"view";
            var e=engine.evidenceStatus(player.getUniqueId()); if(e.isEmpty()){sender.sendMessage("no admitted session");return true;} var es=e.get();
            switch(op){
                case "view" -> { for(String line:es.live())sender.sendMessage(line); return true; }
                case "recent" -> { sender.sendMessage("recent evidence ingests ("+es.recent().size()+"):"); for(String line:es.recent())sender.sendMessage("  "+line); return true; }
                case "side" -> { sender.sendMessage("side-chain (controlled injection) evidence - never the live chain:"); for(String line:es.side())sender.sendMessage(line); for(String line:es.sideRecent())sender.sendMessage("  "+line); return true; }
                case "reingest" -> { sender.sendMessage(engine.evidenceReingest(player.getUniqueId())); return true; }
                default -> { sender.sendMessage("Usage: /papea2ac evidence <name> [view | recent | side | reingest]"); return true; }
            }
        }else if(args.length>=2&&args[0].equalsIgnoreCase("validate")){
            // Phase 8 diagnostic: the live movement validation of a session (classification + reasons + completeness + residual summary); bounded; never a level, never a punishment.
            Player player=getServer().getPlayerExact(args[1]);
            if(player==null){sender.sendMessage("Player is not online.");return true;}
            String op=args.length>=3?args[2].toLowerCase(Locale.ROOT):"view";
            var v=engine.validationStatus(player.getUniqueId()); if(v.isEmpty()){sender.sendMessage("no admitted session");return true;} var st=v.get();
            switch(op){
                case "view" -> {
                    sender.sendMessage("validation session="+st.sessionEpoch()+" anchored="+st.anchored()+" ("+st.anchorKind()+" seq="+st.anchorSequence()+") frontier="+st.frontierNodes()+(st.frontierComplete()?" complete":" INCOMPLETE")+" transitions="+st.transitions()+" VALID="+st.valid()+" UNCERTAIN="+st.uncertain()+" INVALID="+st.invalid()+(st.injectionTicksPending()>0?" injectionPending="+st.injectionTicksPending():""));
                    for(String line:st.context().split("\n"))sender.sendMessage(line);
                    sender.sendMessage("last: "+st.lastClassification()+" seq="+st.lastStartSequence()+"->"+st.lastEndSequence());
                    for(String line:st.lastResult())sender.sendMessage("  "+line);
                    return true; }
                case "recent" -> { sender.sendMessage("recent ("+st.recent().size()+"):"); for(String line:st.recent())sender.sendMessage("  "+line); if(!st.injected().isEmpty()){sender.sendMessage("injected:");for(String line:st.injected())sender.sendMessage("  "+line);} return true; }
                case "notable" -> { sender.sendMessage("notable results (INVALID / UNCERTAIN / VALID under an incomplete context; chain INVALID="+st.invalid()+" UNCERTAIN="+st.uncertain()+"; last "+st.notable().size()+" lines):"); for(String line:st.notable())sender.sendMessage("  "+line); return true; }
                case "inject" -> { // controlled impossible-movement harness (brief 54): synthetic transmissions on a SIDE chain; the live chain and the timeline never see them
                    if(args.length<6){sender.sendMessage("Usage: /papea2ac validate <name> inject <dx> <dy> <dz> [ticks]");return true;}
                    try{double dx=Double.parseDouble(args[3]),dy=Double.parseDouble(args[4]),dz=Double.parseDouble(args[5]);int ticks=args.length>=7?Integer.parseInt(args[6]):1;
                        sender.sendMessage(engine.validationInject(player.getUniqueId(),new me.nicolas.papea2ac.model.Vector3(dx,dy,dz),ticks));}
                    catch(IllegalArgumentException e){sender.sendMessage("Usage: /papea2ac validate <name> inject <dx> <dy> <dz> [ticks]");}
                    return true; }
                case "forensic" -> { // P9-F2 diagnostic only: arm / report / clear the one-shot capture of the next live INVALID
                    String action=args.length>=4?args[3].toLowerCase(java.util.Locale.ROOT):"report";
                    String out=engine.validationForensic(player.getUniqueId(),action);
                    if(action.equals("report")&&!out.startsWith("no trigger")&&!out.startsWith("REFUSED")){
                        try{java.nio.file.Path p=getDataFolder().toPath().resolve("p9f2-forensic-"+player.getName()+".log");java.nio.file.Files.createDirectories(p.getParent());java.nio.file.Files.writeString(p,out+"\n",java.nio.charset.StandardCharsets.UTF_8);
                            sender.sendMessage("forensic capture written to "+p+" ("+out.lines().count()+" lines)");}
                        catch(java.io.IOException e){sender.sendMessage("forensic capture could not be written: "+e);}
                        for(String line:out.lines().limit(12).toList())sender.sendMessage("  "+line);
                    } else sender.sendMessage(out);
                    return true; }
                default -> { sender.sendMessage("Usage: /papea2ac validate <name> [view | recent | notable | forensic [arm|report|clear] | inject <dx> <dy> <dz> [ticks]]"); return true; }
            }
        }else if(args.length>0&&!args[0].equalsIgnoreCase("status")){
            sender.sendMessage("Usage: /papea2ac [status | player <name> | timeline <name> | world <name> | collision <name> | physics <name> [step <scenario> | arm <scenario> | report | cancel] | reconstruct <name> [arm [scenario] | report | cancel] | timing <name> [view | arm [scenario] | report | cancel | velocity x y z | teleport dx dy dz] | validate <name> [view | recent | notable | forensic [arm|report|clear] | inject dx dy dz [ticks]] | evidence <name> [view | recent | side | reingest] | capture on|off | effects suspend|resume | recording <name> on|off | checkpoint <name>]");return true;
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
        var phy=engine.physicsEngineStatus();
        sender.sendMessage("physics profile="+phy.profile()+" sha256="+phy.profileDigest().substring(0,16)+" sine="+phy.sineDigest().substring(0,16)+" blockTable="+phy.blockTableDigest().substring(0,16)+" blocks="+phy.blocks()+" fluidStates="+phy.fluidStates()
            +" effects="+phy.effects()+" attributes="+phy.attributes()+" diagnosticSteps="+phy.diagnosticSteps()+" diagnosticExact="+phy.diagnosticExact()+" activeTraces="+phy.activeTraces());
        var rec=engine.reconstructionEngineStatus();
        sender.sendMessage("reconstruction rawDomain="+rec.rawDomain()+" classes="+rec.contextFreeClasses()+" observation="+rec.observationProfile()+" caps="+rec.maxNodes()+"nodes/"+rec.maxEvaluations()+"evals/"+rec.maxTicks()+"ticks diagnosticRequests="+rec.diagnosticRequests()
            +" diagnosticDeclaredPresent="+rec.diagnosticDeclaredPresent()+" diagnosticSamples="+rec.diagnosticSamples()+" activeTraces="+rec.activeTraces());
        var tim=engine.timingEngineStatus();
        sender.sendMessage("timing profile="+tim.timingProfile()+" markerPort="+tim.markerPortPresent()+" markersIssued="+tim.markersIssued()+" sendFailures="+tim.markerSendFailures()+" answered="+tim.markersAnswered()+" rttSamples="+tim.rttSamples()+" outliers="+tim.rttOutliers()+" rejected="+tim.rttRejected()
            +" lateOrUnknownAcks="+tim.lateOrUnknownAcks()+" expiredMarkers="+tim.expiredMarkers()+" staleTeleportConfirms="+tim.staleTeleportConfirms()+" velocityIgnoredUnknownSelf="+tim.velocityIgnoredUnknownSelf()+" activeTraces="+tim.activeTraces()+" tracerSteps="+tim.tracerSteps()+" explained="+tim.tracerExplained()+" unexplained="+tim.tracerUnexplained());
        var val=engine.validationEngineStatus();
        sender.sendMessage("validation requests="+val.requests()+" VALID="+val.valid()+" UNCERTAIN="+val.uncertain()+" INVALID="+val.invalid()+" uncertain{searchLimit="+val.uncertainSearchLimit()+" unsupported="+val.uncertainUnsupported()+" worldIncomplete="+val.uncertainWorldIncomplete()+" externalMotion="+val.uncertainExternalMotion()+" unanchored="+val.uncertainUnanchored()+" other="+val.uncertainOther()+"} timingBranches="+val.timingBranches()+" reconstructions="+val.reconstructions()+" evaluations="+val.evaluations()+" anchors="+val.anchors()+"/lost="+val.anchorsLost()+" internalErrors="+val.internalErrors()+" injected="+val.injectedRequests());
        var ev=engine.evidenceEngineStatus();
        sender.sendMessage("evidence consumed="+ev.consumed()+" witness="+ev.legitimateWitness()+" unresolved="+ev.unresolved()+" positiveEvents="+ev.positiveEvents()+" duplicates="+ev.duplicates()+" proofRejected="+ev.proofRejected()+" outOfOrder="+ev.outOfOrder()+" sessionMismatch="+ev.sessionMismatch()+" internalErrors="+ev.internalErrors()+" side{consumed="+ev.sideConsumed()+" positive="+ev.sidePositiveEvents()+" duplicates="+ev.sideDuplicates()+"} sessions="+ev.sessionsCreated());
        sender.sendMessage(engine.combatStatusLine()); // Phase 11: engine-wide descriptive combat counters (core-rendered)
        var col=engine.collisionEngineStatus();
        sender.sendMessage("collision profile="+col.profile()+" shapeTable="+col.tableStates()+"states/"+col.tableShapes()+"shapes/"+col.tableBlocks()+"blocks sha256="+col.tableDigest().substring(0,16)+" queries="+col.queries()+" complete="+col.complete()+" incomplete="+col.incomplete()
            +" unavailable="+col.unavailable()+" rejected="+col.rejected()+" truncated="+col.truncated()+" cells="+col.cellsVisited()+" hits="+col.hits()+" cacheHits="+col.shapeCacheHits()+" cacheMisses="+col.shapeCacheMisses()
            +" unsupportedStates="+col.unsupportedStates()+" contextRequired="+col.contextRequired()+" dynamicCells="+col.dynamicCells()+" worldUnknownCells="+col.worldUnknownCells());
        return true;
    }
    /** Main-thread, server-current facts for the physics diagnostic (labelled as such in the output); primitives only cross into core. */
    private static me.nicolas.papea2ac.core.PhysicsQualification.ArmFacts armFacts(Player player){
        var border=player.getWorld().getWorldBorder();double half=border.getSize()/2.0;var c=border.getCenter();
        boolean noEntities=player.getNearbyEntities(8,8,8).isEmpty();
        var boots=player.getInventory().getBoots();boolean leather=boots!=null&&boots.getType()==org.bukkit.Material.LEATHER_BOOTS;
        var effects=new ArrayList<String>();for(var e:player.getActivePotionEffects())effects.add(e.getType().getKey().asString()+"#"+e.getAmplifier());
        var bases=new LinkedHashMap<String,Double>();
        for(var a:new org.bukkit.attribute.Attribute[]{org.bukkit.attribute.Attribute.MOVEMENT_SPEED,org.bukkit.attribute.Attribute.STEP_HEIGHT,org.bukkit.attribute.Attribute.GRAVITY,org.bukkit.attribute.Attribute.JUMP_STRENGTH,org.bukkit.attribute.Attribute.SNEAKING_SPEED,org.bukkit.attribute.Attribute.MOVEMENT_EFFICIENCY,org.bukkit.attribute.Attribute.SCALE}){
            var inst=player.getAttribute(a);if(inst!=null)bases.put(a.getKey().asString(),inst.getBaseValue());
        }
        return new me.nicolas.papea2ac.core.PhysicsQualification.ArmFacts(true,c.getX()-half,c.getZ()-half,c.getX()+half,c.getZ()+half,noEntities,leather,effects,bases);
    }
    @EventHandler public void join(PlayerJoinEvent event){attach(event.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent event){Binding b=bindings.remove(event.getPlayer().getUniqueId());if(b!=null)capture.detach(b.channel(),b.session());}
    private int configuredCapacity=Integer.MAX_VALUE;
    private static int parseInt(String v){try{return v==null?-1:Integer.parseInt(v.trim());}catch(NumberFormatException e){return -1;}}
    private boolean tc25Passed(){
        try{var f=getDataFolder().toPath().resolve("tc25-result.properties");if(!Files.exists(f)||Files.size(f)>4096)return false;
            var props=new java.util.Properties();try(var in=Files.newInputStream(f)){props.load(in);}return "true".equals(props.getProperty("pass"))&&parseInt(props.getProperty("gateSessions"))>=configuredCapacity;} // TC25 must hold at the configured session capacity (NEW-M1)
        catch(IOException e){return false;}
    }
    private java.util.List<String> mitigationLines(me.nicolas.papea2ac.mitigation.MitigationActor.Status st){
        var out=new java.util.ArrayList<String>();long now=System.nanoTime();
        out.add("mitigation session="+st.session().epoch()+" policy="+st.policy()+" mode="+(engine.effectsSuspended()?"OBSERVE_ONLY/DISABLED (no effects)":"ENFORCE")+" liveChain="+st.liveChain()+"/"+me.nicolas.papea2ac.mitigation.SetbackPolicy.REQUIRED_GROUPS+" sideChain="+st.sideChain()+"/"+me.nicolas.papea2ac.mitigation.SetbackPolicy.REQUIRED_GROUPS+(st.disabled()?" SESSION_DISABLED":""));
        out.add("  intent="+st.activeState()+(st.activeActionId()==null?"":" action="+st.activeActionId().substring(0,16))+" cooldown="+(st.lastSentNanos()==Long.MIN_VALUE?"none":Math.max(0,(2_000_000_000L-(now-st.lastSentNanos()))/1_000_000L)+"ms")+" recentFailures="+st.recentFailures());
        out.add("  anchors="+st.anchors()+(st.latestAnchor()==null?" (none)":" latest "+st.latestAnchor().describe()+" age="+((now-st.latestAnchor().nanos())/1_000_000L)+"ms")+(st.lastAnchorRefusal()==null?"":" lastRefusal="+st.lastAnchorRefusal()));
        out.add("  last decision: "+(st.lastDecision()==null?"none":st.lastDecision().describe()));
        for(var d:st.recentDecisions().subList(Math.max(0,st.recentDecisions().size()-6),st.recentDecisions().size()))out.add("    "+d.describe());
        for(var o:st.recentOutcomes())out.add("  outcome "+o.actionId().substring(0,16)+" "+o.terminal()+" "+o.reason()+" (reached "+o.reachedState()+")");
        out.add("  executor: "+mitigation.last()+" polled="+mitigation.polled.sum()+" started="+mitigation.started.sum()+" sent="+mitigation.sent.sum()+" rejected="+mitigation.rejected.sum());
        out.add("  global G2 proposal guard: "+engine.proposalGuardStatus()+" executorSuspends="+mitigation.globalSuspends.sum()+" capsules promoted="+engine.capsulesPromoted.sum()+" unavailable="+engine.capsulesUnavailable.sum()+" durable="+engine.capsulesDurable.sum()+" failed="+engine.capsulesFailed.sum());
        return out;
    }
    @Override public void onDisable(){
        if(mitigation!=null&&engine!=null)mitigation.shutdown(); // MITIGATION.md: "Shutdown invalidates all queued intents before hooks are removed"
        HandlerList.unregisterAll((Listener)this);
        getServer().getServicesManager().unregisterAll(this);
        if(capture!=null){capture.unregisterEarlyHook();for(Binding b:bindings.values())capture.detach(b.channel(),b.session());}
        bindings.clear();
        if(engine!=null){engine.close();if(!engine.awaitTermination(2000))getLogger().warning("Papea2AC shards did not drain within 2 s of disable; sessions were force-fenced.");}// drain before Bukkit closes the plugin jar (P3-F13)
    }
}
