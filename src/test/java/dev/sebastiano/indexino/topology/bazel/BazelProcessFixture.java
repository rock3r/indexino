package dev.sebastiano.indexino.topology.bazel;

import java.nio.file.Files;
import java.nio.file.Path;

/** Invented direct client; never launches Bazel or any descendant. */
public final class BazelProcessFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("output")) {
            for (int i = 0; i < 12000; i++) {
                System.out.println("//invented:Source" + i + ".kt");
            }
            System.err.println("diagnostic");
            System.exit(7);
        }
        if (args[0].equals("closed-output")) {
            System.out.close();
            System.err.close();
        }
        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        Thread.sleep(120000);
    }
}
