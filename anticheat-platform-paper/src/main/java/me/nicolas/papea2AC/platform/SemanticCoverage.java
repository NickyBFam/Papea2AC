package me.nicolas.papea2AC.platform;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Semantic claims are distinct from the numeric-ID fingerprint and from transport qualification. */
public final class SemanticCoverage {
    private SemanticCoverage(){}
    public record Entry(String inventoryKey,boolean mandatory,String status,String representation){}
    public static List<Entry> entries()throws IOException{
        try(var stream=SemanticCoverage.class.getResourceAsStream("/semantic-coverage.tsv")){
            if(stream==null)throw new IOException("semantic coverage resource missing");
            try(var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
                var result=new ArrayList<Entry>();String line;
                Set<String> statuses=Set.of("FULLY NORMALIZED","METADATA-ONLY","INTENTIONALLY UNSUPPORTED","NOT REQUIRED UNTIL LATER PHASE");
                while((line=reader.readLine())!=null){
                    if(line.startsWith("#"))continue;
                    String[] fields=line.split("\t",-1);
                    if(fields.length!=7||!statuses.contains(fields[5])||result.size()>=1024
                        ||!Set.of("true","false").contains(fields[4]))throw new IOException("invalid coverage entry");
                    result.add(new Entry(String.join("\t",Arrays.copyOf(fields,4)),Boolean.parseBoolean(fields[4]),fields[5],fields[6]));
                }
                return List.copyOf(result);
            }
        }
    }
    public static void verify(List<PaperPacketInventory.Entry> nativeEntries)throws IOException{
        var entries=entries();var actual=nativeEntries.stream().map(PaperPacketInventory.Entry::canonical).toList();
        if(!entries.stream().map(Entry::inventoryKey).toList().equals(actual))throw new IOException("semantic coverage does not match pinned native inventory");
        if(new HashSet<>(actual).size()!=actual.size())throw new IOException("duplicate coverage entry");
    }
    public static List<Entry> missingMandatory()throws IOException{
        return entries().stream().filter(e->e.mandatory()&&!e.status().equals("FULLY NORMALIZED")).toList();
    }
}
