package com.mrnobody.agent.tools;

import java.io.File;
import java.io.IOException;

/**
 * Confines a terminal-requested path to a workspace root.
 *
 * <p>The terminal tool must not be a way for the agent to read arbitrary user
 * files. Its only filesystem operation is hashing a file, and that file has to
 * live inside the app's own workspace — the same directory the clear-data
 * screen empties under its {@code workspace} bucket. A path that resolves
 * outside that root (an absolute path to a photo, a {@code ..} escape, a
 * symlink) is refused.
 *
 * <p>Pure Java on purpose: the Android {@code Context} is not available on the
 * test JVM, so the decision logic lives here where it can be unit-tested, and
 * {@link TerminalTool} supplies the root from {@code context.getFilesDir()}.
 */
public final class WorkspacePath {

    private WorkspacePath() {
    }

    /**
     * Resolve {@code path} against {@code root}, refusing anything that escapes
     * it. Returns null when the path is outside the root or cannot be resolved.
     */
    public static File resolveWithin(File root, String path) {
        if (root == null || path == null || path.trim().isEmpty()) return null;

        File candidate = new File(path);
        if (!candidate.isAbsolute()) {
            candidate = new File(root, path);
        }
        try {
            String rootCanon = root.getCanonicalPath();
            String candidateCanon = candidate.getCanonicalPath();
            // The candidate must equal the root or live strictly beneath it.
            if (candidateCanon.equals(rootCanon)) return candidate;
            if (candidateCanon.startsWith(rootCanon + File.separator)) return candidate;
            return null;
        } catch (IOException e) {
            return null;
        }
    }
}
