package com.mrnobody.agent.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the on-device subset of terminal commands, sandboxed to a workspace.
 *
 * <p>This is the real execution path the terminal tool was missing: the tool
 * previously only accepted {@code sha256 <path>} and everything else was
 * refused or "unknown", so a task could never actually run anything. This
 * class runs the commands Android genuinely ships (toybox) — hashing, listing,
 * reading — confined to the workspace directory, with a timeout and an output
 * cap. It never runs a shell, so there is no quoting or injection surface; it
 * runs the named binary directly.
 *
 * <p>Commands that need a toolchain the device does not ship (git, python,
 * node) are <em>not</em> run here: {@link com.mrnobody.agent.policy.PolicyGate#requiresRemote}
 * recognises them and the caller reports them honestly, because they run on
 * the remote worker instead.
 *
 * <p>Pure enough to test on the JVM: {@code ls}/{@code cat}/{@code head}/
 * {@code stat} exist on any host, so the process path and the workspace
 * confinement are exercised without a device.
 */
public final class TerminalRuntime {

    /** Hard cap on captured output, so a huge file cannot flood the result. */
    private static final int MAX_OUTPUT = 8192;

    /** How long a command may run before it is killed. */
    private static final long TIMEOUT_MS = 10_000;

    /** The binaries a command may name. Deliberately small and read-only. */
    private static final java.util.Set<String> BINARIES =
            java.util.Set.of("ls", "cat", "head", "stat");

    /** A command's outcome: success plus its output, or a failure reason. */
    public static final class Result {
        public final boolean ok;
        public final String output;

        Result(boolean ok, String output) {
            this.ok = ok;
            this.output = output;
        }

        static Result ok(String output) {
            return new Result(true, output);
        }

        static Result fail(String output) {
            return new Result(false, output);
        }
    }

    private TerminalRuntime() {
    }

    /**
     * Run {@code command} confined to {@code workspace}.
     */
    public static Result run(File workspace, String command) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty()) return Result.fail("empty command");

        // Hashing is done in-Java: no process, no PATH, fully confined.
        if (cmd.startsWith("sha256 ") || cmd.startsWith("md5 ") || cmd.startsWith("hash ")) {
            return hash(workspace, cmd);
        }
        if (cmd.startsWith("unzip ") || cmd.equals("unzip")) {
            return unzip(workspace, cmd);
        }
        if (cmd.startsWith("inspect ") || cmd.startsWith("file ") || cmd.startsWith("wc ")) {
            return inspect(workspace, cmd);
        }

        return runProcess(workspace, cmd);
    }

    /** {@code sha256 <path>} / {@code md5 <path>}, the path confined to the workspace. */
    private static Result hash(File workspace, String cmd) {
        int sp = cmd.indexOf(' ');
        String algo = cmd.substring(0, sp);
        String path = cmd.substring(sp + 1).trim();
        File f = WorkspacePath.resolveWithin(workspace, path);
        if (f == null) return Result.fail("path is outside the workspace: " + path);
        if (!f.exists() || !f.isFile()) return Result.fail("file not found: " + path);
        try {
            String digestAlgo = "sha256".equals(algo) ? "SHA-256" : "MD5";
            MessageDigest md = MessageDigest.getInstance(digestAlgo);
            byte[] buf = new byte[8192];
            int n;
            try (FileInputStream in = new FileInputStream(f)) {
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return Result.ok(algo + "(" + f.getName() + ") = " + sb);
        } catch (Exception e) {
            return Result.fail("hash failed: " + e.getMessage());
        }
    }

    /** Unzip a workspace zip into a workspace folder. Zip-slip is refused. */
    private static Result unzip(File workspace, String cmd) {
        String rest = cmd.length() > 6 ? cmd.substring(6).trim() : "";
        if (rest.isEmpty()) return Result.fail("unzip needs a zip path");
        String[] bits = rest.split("\\s+");
        File zip = WorkspacePath.resolveWithin(workspace, bits[0]);
        if (zip == null || !zip.isFile()) return Result.fail("zip is outside the workspace or missing");
        File dest = bits.length > 1
                ? WorkspacePath.resolveWithin(workspace, bits[1])
                : new File(workspace, stripExt(zip.getName()));
        if (dest == null) return Result.fail("destination escapes the workspace");
        dest.mkdirs();
        int n = 0;
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
                new java.io.FileInputStream(zip))) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = in.getNextEntry()) != null) {
                File out = WorkspacePath.resolveWithin(dest, e.getName());
                if (out == null) return Result.fail("refused zip entry that escapes: " + e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    int r;
                    while ((r = in.read(buf)) != -1) fos.write(buf, 0, r);
                }
                n++;
                if (n > 200) return Result.fail("zip has too many entries");
            }
        } catch (Exception ex) {
            return Result.fail("unzip failed: " + ex.getMessage());
        }
        return Result.ok("extracted " + n + " file(s) to " + dest.getName());
    }

    private static Result inspect(File workspace, String cmd) {
        int sp = cmd.indexOf(' ');
        String path = sp < 0 ? "" : cmd.substring(sp + 1).trim();
        if (path.isEmpty()) return Result.fail("inspect needs a path");
        File f = WorkspacePath.resolveWithin(workspace, path);
        if (f == null) return Result.fail("path is outside the workspace: " + path);
        if (!f.exists()) return Result.fail("not found: " + path);
        String kind = f.isDirectory() ? "directory" : "file";
        return Result.ok(kind + " " + f.getName() + " " + f.length() + " bytes");
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name + "-out";
    }

    /** {@code ls}/{@code cat}/{@code head}/{@code stat}, run in the workspace. */
    private static Result runProcess(File workspace, String cmd) {
        String[] parts = cmd.trim().split("\\s+");
        String bin = parts[0];
        if (!BINARIES.contains(bin)) {
            return Result.fail("unsupported command: " + bin);
        }

        List<String> argv = new ArrayList<>();
        argv.add(bin);
        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i];
            // A path-like argument must resolve inside the workspace; an
            // absolute path or a .. escape is refused, never followed.
            if (arg.contains("/") || arg.equals("..") || arg.contains("../")) {
                File f = WorkspacePath.resolveWithin(workspace, arg);
                if (f == null) return Result.fail("argument escapes the workspace: " + arg);
                argv.add(f.getAbsolutePath());
            } else {
                argv.add(arg);
            }
        }

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.directory(workspace);
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process proc = p;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    int total = 0;
                    while ((n = in.read(buf)) != -1) {
                        int keep = Math.min(n, MAX_OUTPUT - total);
                        if (keep <= 0) break;
                        out.write(buf, 0, keep);
                        total += keep;
                    }
                } catch (Exception ignored) {
                }
            });
            reader.start();

            if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return Result.fail("command timed out");
            }
            reader.join(1000);
            int code = p.exitValue();
            String output = out.toString("UTF-8").trim();
            if (code != 0) {
                return Result.fail(output.isEmpty() ? "command failed (exit " + code + ")" : output);
            }
            return Result.ok(output);
        } catch (Exception e) {
            return Result.fail("command failed: " + e.getMessage());
        } finally {
            if (p != null) p.destroy();
        }
    }
}
