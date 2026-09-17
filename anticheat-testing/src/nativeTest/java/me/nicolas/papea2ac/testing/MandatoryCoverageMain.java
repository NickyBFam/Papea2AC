package me.nicolas.papea2ac.testing;

import me.nicolas.papea2AC.platform.SemanticCoverage;

/** Release qualification gate, intentionally separate from regression tests of fail-closed operation. */
public final class MandatoryCoverageMain {
    private MandatoryCoverageMain(){}
    public static void main(String[] args)throws Exception{
        var missing=SemanticCoverage.missingMandatory();
        for(var entry:missing)System.out.println(entry.inventoryKey()+"\t"+entry.status()+"\t"+entry.representation());
        if(!missing.isEmpty())throw new IllegalStateException("Missing mandatory semantic coverage: "+missing.size());
    }
}
