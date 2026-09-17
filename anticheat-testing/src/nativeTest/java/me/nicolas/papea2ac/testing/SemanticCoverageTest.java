package me.nicolas.papea2ac.testing;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import me.nicolas.papea2AC.platform.*;

class SemanticCoverageTest {
    @BeforeAll static void bootstrap()throws Exception{NativePacketTest.bootstrap();}
    @Test void everyNativeIdHasAnExplicitSemanticClassification()throws Exception{
        var nativeEntries=PaperPacketInventory.inspect();SemanticCoverage.verify(nativeEntries);
        assertEquals(256,SemanticCoverage.entries().size());
        var changed=new ArrayList<>(nativeEntries);changed.removeLast();
        assertThrows(java.io.IOException.class,()->SemanticCoverage.verify(changed));
    }
    @Test void publishedInventoryMatchesStartupResourceAndDoesNotHideMandatoryGaps()throws Exception{
        Path root=Path.of(System.getProperty("repo.root"));
        assertEquals(Files.readString(root.resolve("docs/phase1-artifacts/packet-coverage.tsv")),Files.readString(root.resolve("anticheat-platform-paper/src/main/resources/semantic-coverage.tsv")));
        assertTrue(SemanticCoverage.missingMandatory().isEmpty(),"all 127 mandatory entries are closed as of the semantic normalization pass");
        assertTrue(SemanticCoverage.entries().stream().filter(e->e.status().equals("FULLY NORMALIZED")).allMatch(e->!e.representation().isBlank()));
    }
}
