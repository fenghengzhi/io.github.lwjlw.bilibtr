package io.github.lwjlw.bilibtr.proxy;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

/** Real filesystem regression: a second process can open the base file during every UI update. */
public class ConfigFilePersistenceTest {
    @Test public void concurrentReadersOnlySeeCompleteSnapshots() throws Exception {
        Path directory = Files.createTempDirectory("bilibtr-config-test-");
        Path target = directory.resolve("btr.conf");
        String previous = "enabled=true\nlastms=0\n# 旧配置\n";
        String next = "enabled=false\nlastms=12000\n# " + String.join("", Collections.nCopies(4096, "新")) + "\n";
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicBoolean writing = new AtomicBoolean(true);
        CountDownLatch reading = new CountDownLatch(1);
        try {
            ConfigServer.writeConfigAtomically(target.toFile(), previous);
            Future<Integer> observations = worker.submit(() -> {
                int count = 0;
                do {
                    String actual = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
                    assertTrue("Reader observed an empty/partial/mixed config of " + actual.length() + " characters",
                            actual.equals(previous) || actual.equals(next));
                    count++;
                    reading.countDown();
                } while (writing.get());
                return count;
            });
            assertTrue(reading.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 40; i++) {
                ConfigServer.writeConfigAtomically(target.toFile(), (i & 1) == 0 ? next : previous);
            }
            writing.set(false);
            assertTrue(observations.get(5, TimeUnit.SECONDS) > 1);
            assertEquals(previous, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
            try (Stream<Path> files = Files.list(directory)) {
                assertEquals("Staging files must be cleaned up", 1L, files.count());
            }
        } finally {
            writing.set(false);
            worker.shutdownNow();
            deleteTree(directory);
        }
    }

    @Test public void stagingFailureLeavesExistingConfigUntouched() throws Exception {
        Path directory = Files.createTempDirectory("bilibtr-config-failure-");
        Path target = directory.resolve("btr.conf");
        String previous = "enabled=true\nlastms=0\n";
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
        try {
            ConfigServer.writeConfigAtomically(target.toFile(), previous);
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
            // Root can bypass directory permissions; this failure simulation then cannot apply.
            assumeFalse(Files.isWritable(directory));
            try {
                ConfigServer.writeConfigAtomically(target.toFile(), "enabled=false\n");
                fail("Staging in a non-writable directory should fail");
            } catch (IOException expected) { }
            assertEquals(previous, new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
            deleteTree(directory);
        }
    }

    @Test public void failedRenameCleansStagingAndPreservesDestination() throws Exception {
        Path directory = Files.createTempDirectory("bilibtr-config-rename-");
        Path target = Files.createDirectory(directory.resolve("btr.conf"));
        Path existing = target.resolve("existing");
        Files.write(existing, new byte[]{1, 2, 3});
        try {
            try {
                ConfigServer.writeConfigAtomically(target.toFile(), "enabled=false\n");
                fail("A nonempty directory cannot be replaced by a config file");
            } catch (IOException expected) { }
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(existing));
            try (Stream<Path> files = Files.list(directory)) {
                assertEquals("Failed commits must not leave staging files", 1L, files.count());
            }
        } finally { deleteTree(directory); }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(file);
        }
    }
}
