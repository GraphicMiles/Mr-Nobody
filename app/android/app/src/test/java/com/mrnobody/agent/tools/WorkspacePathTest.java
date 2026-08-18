package com.mrnobody.agent.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * The terminal's filesystem sandbox. The whole point is that an agent-proposed
 * path can never reach outside the app workspace — so the escape cases carry
 * as much weight as the accept cases.
 */
public class WorkspacePathTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aPathInsideTheRootIsResolved() throws Exception {
        File root = tmp.getRoot();
        File inside = tmp.newFolder("workspace");
        assertNotNull(WorkspacePath.resolveWithin(inside, "report.pdf"));
        assertNotNull(WorkspacePath.resolveWithin(inside, new File(inside, "a.txt").getAbsolutePath()));
    }

    @Test
    public void aDotDotEscapeIsRefused() throws Exception {
        File root = tmp.getRoot();
        File workspace = tmp.newFolder("workspace");
        File secret = tmp.newFile("secret.txt"); // lives next to workspace, not in it

        assertNull(WorkspacePath.resolveWithin(workspace, "../secret.txt"));
        assertNull(WorkspacePath.resolveWithin(workspace, new File(secret.getAbsolutePath()).getAbsolutePath()));
    }

    @Test
    public void anAbsolutePathOutsideTheRootIsRefused() throws Exception {
        File workspace = tmp.newFolder("workspace");
        File outside = tmp.newFile("outside.txt");

        assertNull(WorkspacePath.resolveWithin(workspace, outside.getAbsolutePath()));
    }

    @Test
    public void anEmptyOrNullPathIsRefused() throws Exception {
        File workspace = tmp.newFolder("workspace");
        assertNull(WorkspacePath.resolveWithin(workspace, null));
        assertNull(WorkspacePath.resolveWithin(workspace, ""));
        assertNull(WorkspacePath.resolveWithin(workspace, "   "));
        assertNull(WorkspacePath.resolveWithin(null, "x"));
    }

    @Test
    public void theRootItselfResolves() throws Exception {
        File workspace = tmp.newFolder("workspace");
        assertEquals(workspace.getCanonicalPath(),
                WorkspacePath.resolveWithin(workspace, workspace.getAbsolutePath()).getCanonicalPath());
    }
}
