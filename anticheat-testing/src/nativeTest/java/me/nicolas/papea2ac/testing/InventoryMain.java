package me.nicolas.papea2ac.testing;

import java.nio.file.*;
import me.nicolas.papea2AC.platform.PaperPacketInventory;

/** Explicit maintainer capture command; tests never rewrite approved inventory expectations. */
public final class InventoryMain {
    private InventoryMain() {}
    public static void main(String[] args) throws Exception {
        NativePacketTest.bootstrap();
        Files.writeString(Path.of(args[0]),PaperPacketInventory.canonical(PaperPacketInventory.inspect()));
    }
}
