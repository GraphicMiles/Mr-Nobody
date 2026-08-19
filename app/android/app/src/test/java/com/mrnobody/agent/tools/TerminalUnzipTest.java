package com.mrnobody.agent.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class TerminalUnzipTest {

    @Test
    public void unzipExtractsInsideTheWorkspace() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "mn-unzip");
        File inner = new File(root, "in");
        inner.mkdirs();
        File zip = new File(inner, "pack.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("hello.txt"));
            z.write("hi".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        TerminalRuntime.Result r = TerminalRuntime.run(inner, "unzip pack.zip");
        assertTrue(r.output, r.ok);
        assertTrue(new File(inner, "pack/hello.txt").isFile()
                || new File(inner, "hello.txt").isFile()
                || r.output.contains("extracted"));
    }

    @Test
    public void zipSlipIsRefused() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "mn-slip");
        root.mkdirs();
        File zip = new File(root, "evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
            z.putNextEntry(new ZipEntry("../outside.txt"));
            z.write("no".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        TerminalRuntime.Result r = TerminalRuntime.run(root, "unzip evil.zip");
        assertFalse(r.ok);
        assertTrue(r.output, r.output.toLowerCase().contains("escape")
                || r.output.toLowerCase().contains("refused"));
    }

    @Test
    public void inspectReportsSize() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "mn-ins");
        root.mkdirs();
        File f = new File(root, "a.txt");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("abcd".getBytes(StandardCharsets.UTF_8));
        }
        TerminalRuntime.Result r = TerminalRuntime.run(root, "inspect a.txt");
        assertTrue(r.output, r.ok);
        assertTrue(r.output, r.output.contains("4"));
    }
}
