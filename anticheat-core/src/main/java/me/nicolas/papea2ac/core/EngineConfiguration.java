package me.nicolas.papea2ac.core;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** Phase 1 startup subset. Strict properties syntax: comments and order have no semantic identity. */
public record EngineConfiguration(int schemaVersion,RuntimeControl.Mode mode,int workers,int capacity,long totalBytes) {
    public EngineConfiguration {
        if(schemaVersion!=1||mode==null||workers<1||workers>8||capacity<1||capacity>workers*128
            ||totalBytes<0||totalBytes-totalBytes/5 < (256L+2L*capacity)*1024*1024)
            throw new IllegalArgumentException("invalid configuration/floor");
        if(mode==RuntimeControl.Mode.ENFORCE)throw new IllegalArgumentException("no production certification profile");
    }
    public static EngineConfiguration parse(String text,long maxHeap){
        if(text.length()>16384)throw new IllegalArgumentException("config bytes");
        Map<String,String> values=new TreeMap<>();
        Set<String> keys=Set.of("schemaVersion","mode","workers","capacity","totalBytes");
        for(String line:text.split("\\R")){
            String s=line.strip();if(s.isEmpty()||s.startsWith("#"))continue;
            String[] pair=s.split("=",2);
            if(pair.length!=2||!keys.contains(pair[0].strip())||values.putIfAbsent(pair[0].strip(),pair[1].strip())!=null)
                throw new IllegalArgumentException("unknown/duplicate key");
        }
        return new EngineConfiguration(Integer.parseInt(values.getOrDefault("schemaVersion","1")),
            RuntimeControl.Mode.valueOf(values.getOrDefault("mode","OBSERVE_ONLY")),
            Integer.parseInt(values.getOrDefault("workers","1")),Integer.parseInt(values.getOrDefault("capacity","32")),
            Long.parseLong(values.getOrDefault("totalBytes",Long.toString(Math.min(1L<<30,maxHeap/4)))));
    }
    public String canonical(){
        return "{\"capacity\":"+capacity+",\"mode\":\""+mode+"\",\"schemaVersion\":"+schemaVersion+",\"totalBytes\":"+totalBytes+",\"workers\":"+workers+"}\n";
    }
    public String digest(){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
}
