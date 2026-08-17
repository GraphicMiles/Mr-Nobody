package com.mrnobody.agent.core;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Architecture test: there is exactly one way to run a tool.
 *
 * <p>Every guarantee the agent is about to gain — parameter validation, the
 * approval policy, confirmation, timeouts, the audit record — is enforced in
 * {@code AgentEngine.callTool}. A caller that holds a {@link Tool} and calls
 * {@code execute} itself bypasses all of it, silently, and nothing at runtime
 * would notice. That is exactly how the Flutter "search" channel method used to
 * reach {@code SearchTool} directly.
 *
 * <p>So the rule is checked mechanically, against the source, on every build.
 */
public class ToolCallPathTest {

    /** Files allowed to call {@code execute} on a tool. */
    private static final List<String> ALLOWED = List.of(
            // The single entry point itself.
            "agent/planner/DeterministicEngine.java"
    );

    /** {@code something.execute(context, ...)} — the shape of a direct tool call. */
    private static final Pattern DIRECT_CALL =
            Pattern.compile("\\.execute\\s*\\(\\s*(context|getApplicationContext\\(\\)|ctx)\\b");

    /** {@code new FooTool(...)} outside the places that legitimately register tools. */
    private static final Pattern TOOL_CONSTRUCTION =
            Pattern.compile("new\\s+\\w*Tool\\s*\\(");

    private static final List<String> MAY_CONSTRUCT_TOOLS = List.of(
            "browser/MrNobodyApp.java",              // registers the tool set
            "agent/planner/DeterministicEngine.java" // owns the registry
    );

    @Test
    public void noOneCallsAToolDirectly() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            String rel = relative(file);
            if (ALLOWED.contains(rel)) continue;
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = DIRECT_CALL.matcher(source);
            while (m.find()) {
                // Tool.execute is the only execute(context, ...) in the core;
                // Worker.execute(context, task) is the dispatcher's, not a tool's.
                if (source.startsWith("package com.mrnobody.agent.dispatcher", 0)) continue;
                if (isDispatcherCall(source, m.start())) continue;
                offenders.add(rel + " → " + line(source, m.start()));
            }
        }
        if (!offenders.isEmpty()) {
            fail("Tools must be invoked through AgentEngine.callTool(), which is where "
                    + "validation, policy, confirmation, timeouts and the audit record live.\n"
                    + "Direct calls found:\n  " + String.join("\n  ", offenders));
        }
    }

    @Test
    public void toolsAreConstructedOnlyWhereTheyAreRegistered() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            String rel = relative(file);
            if (MAY_CONSTRUCT_TOOLS.contains(rel)) continue;
            if (rel.startsWith("agent/tools/")) continue; // a tool may build its own helpers
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = TOOL_CONSTRUCTION.matcher(source);
            while (m.find()) {
                offenders.add(rel + " → " + line(source, m.start()));
            }
        }
        if (!offenders.isEmpty()) {
            fail("A tool built outside the registry is a tool nothing governs.\n"
                    + "Constructions found:\n  " + String.join("\n  ", offenders));
        }
    }

    @Test
    public void theEntryPointExists() throws IOException {
        String engine = Files.readString(
                sourceRoot().resolve("agent/core/AgentEngine.java"), StandardCharsets.UTF_8);
        assertTrue("AgentEngine must declare callTool()", engine.contains("callTool("));
    }

    // ------------------------------------------------------------- helpers

    private static boolean isDispatcherCall(String source, int at) {
        // Worker.execute(context, task) — the dispatcher's own seam.
        int lineStart = source.lastIndexOf('\n', at) + 1;
        int lineEnd = source.indexOf('\n', at);
        String text = source.substring(lineStart, lineEnd < 0 ? source.length() : lineEnd);
        return text.contains("task)") || text.contains("task,");
    }

    private static String line(String source, int at) {
        int lineStart = source.lastIndexOf('\n', at) + 1;
        int lineEnd = source.indexOf('\n', at);
        return source.substring(lineStart, lineEnd < 0 ? source.length() : lineEnd).trim();
    }

    private static Path sourceRoot() {
        // Tests run with the Gradle module directory as the working directory.
        Path fromModule = Paths.get("src/main/java/com/mrnobody");
        if (Files.isDirectory(fromModule)) return fromModule;
        return Paths.get("app/android/app/src/main/java/com/mrnobody");
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(sourceRoot())) {
            // Collectors.toList() rather than Stream.toList(): the module
            // compiles at Java 11.
            return files.filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    private static String relative(Path file) {
        return sourceRoot().relativize(file).toString().replace('\\', '/');
    }
}
