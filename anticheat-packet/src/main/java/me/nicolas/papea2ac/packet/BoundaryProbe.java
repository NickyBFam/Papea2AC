package me.nicolas.papea2ac.packet;
import java.util.*;
/** A topology match alone never proves transient integrity or authoritative application. */
public final class BoundaryProbe {
    public record Result(TransportCapability capability,String reason){}
    public Result probe(List<String> expected,List<String> actual,boolean familyInventoryComplete,
                        boolean qualifiedOutcomes,boolean mediatedIntegrity,boolean reconciledBaseline){
        if(expected.size()>128||actual.size()>128)return new Result(new TransportCapability(false,false,false,false),"PipelineTooLarge");
        if(!expected.equals(actual))return new Result(TransportCapability.observeOnly(),"ForeignHandlerInsideBoundary");
        if(!familyInventoryComplete)return new Result(TransportCapability.observeOnly(),"MissingObservationFamily");
        return new Result(new TransportCapability(true,qualifiedOutcomes,mediatedIntegrity,reconciledBaseline),
            qualifiedOutcomes&&mediatedIntegrity&&reconciledBaseline?"Qualified":"UncertifiedOutcome");
    }
}
