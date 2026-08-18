package com.mrnobody.agent.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

/**
 * The on-device terminal execution: real commands, confined to the workspace.
 * Runs on the JVM because ls/cat/head/stat exist on any host, which is exactly
 * what makes the sandbox testable without a device.
 */
public class TerminalRuntimeTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void sha256HashesAFileInsideTheWorkspace() throws Exception {
        File ws = tmp.newFolder("workspace");
        File f = new File(ws, "report.txt");
        Files.write(f.toPath(), "hello".getBytes());

        TerminalRuntime.Result r = TerminalRuntime.run(ws, "sha256 report.txt");
        assertTrue(r.output, r.ok);
        assertTrue(r.output.startsWith("sha256(report.txt) = "));
    }

    @Test
    public void lsListsTheWorkspace() throws Exception {
        File ws = tmp.newFolder("workspace");
        Files.write(new File(ws, "a.txt").toPath(), "a".getBytes());
        Files.write(new File(ws, "b.txt").toPath(), "b".getBytes());

        TerminalRuntime.Result r = TerminalRuntime.run(ws, "ls");
        assertTrue(r.output, r.ok);
        assertTrue(r.output.contains("a.txt"));
        assertTrue(r.output.contains("b.txt"));
    }

    @Test
    public void catReadsAFileInsideTheWorkspace() throws Exception {
        File ws = tmp.newFolder("workspace");
        Files.write(new File(ws, "note.txt").toPath(), "hello world".getBytes());

        TerminalRuntime.Result r = TerminalRuntime.run(ws, "cat note.txt");
        assertTrue(r.output, r.ok);
        assertTrue(r.output.contains("hello world"));
    }

    @Test
    public void aDotDotEscapeIsRefused() throws Exception {
        File ws = tmp.newFolder("workspace");
        File secret = tmp.newFile("secret.txt"); // lives next to workspace
        Files.write(secret.toPath(), "top secret".getBytes());

        TerminalRuntime.Result r = TerminalRuntime.run(ws, "cat ../secret.txt");
        assertFalse(r.ok);
        assertTrue(r.output.contains("escapes"));
    }

    @Test
    public void anAbsolutePathOutsideTheWorkspaceIsRefused() throws Exception {
        File ws = tmp.newFolder("workspace");
        File outside = tmp.newFile("outside.txt");
        Files.write(outside.toPath(), "outside".getBytes());

        TerminalRuntime.Result r = TerminalRuntime.run(ws, "cat " + outside.getAbsolutePath());
        assertFalse(r.ok);
    }

    @Test
    public void anUnknownBinaryIsRefused() {
        File ws = tmp.getRoot();
        TerminalRuntime.Result r = TerminalRuntime.run(ws, "rm -rf /");
        assertFalse(r.ok);
        assertTrue(r.output.contains("unsupported"));
    }

    @Test
    public void anEmptyCommandIsRefused() {
        assertFalse(TerminalRuntime.run(tmp.getRoot(), "").ok);
        assertFalse(TerminalRuntime.run(tmp.getRoot(), null).ok);
    }
}
