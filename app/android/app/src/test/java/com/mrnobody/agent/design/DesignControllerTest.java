package com.mrnobody.agent.design;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.execution.ExecutionIdentity;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class DesignControllerTest {

    @Test
    public void createReviewSelectApproveAndExportAreSeparateTransitions() {
        InMemoryDesignSessions sessions = new InMemoryDesignSessions();
        FakeDesignAdapter adapter = new FakeDesignAdapter();
        DesignController controller = new DesignController(sessions);
        Task task = new Task(7L, "create a sale poster");
        AtomicInteger slot = new AtomicInteger();
        DesignController.Invoker invoker = (request, cancellation) ->
                adapter.invoke(null, request, identity(task, request, slot.getAndIncrement()), cancellation);

        DesignController.Outcome generated = controller.run(null, task,
                "create a sale poster", Cancellation.NONE, invoker);
        assertFalse(generated.failed());
        assertEquals(ReviewGate.APPROVED, generated.session.safetyGate);
        assertEquals(ReviewGate.PENDING, generated.session.creativeGate);
        assertEquals(DesignSession.Status.AWAITING_CREATIVE_REVIEW, generated.session.status);

        DesignController.Outcome selected = controller.run(null, task,
                "use the first option", Cancellation.NONE, invoker);
        assertFalse(selected.session.artifactRef.isEmpty());
        assertEquals(ReviewGate.PENDING, selected.session.creativeGate);

        DesignController.Outcome approved = controller.run(null, task,
                "approve this draft", Cancellation.NONE, invoker);
        assertEquals(ReviewGate.APPROVED, approved.session.creativeGate);
        assertEquals(DesignSession.Status.READY, approved.session.status);

        DesignController.Outcome exported = controller.run(null, task,
                "export as pdf", Cancellation.NONE, invoker);
        assertEquals(ReviewGate.APPROVED, exported.session.finalizationGate);
        assertEquals(DesignSession.Status.FINALIZED, exported.session.status);
        assertTrue(exported.session.exportRef.contains("pdf"));
    }

    @Test
    public void exportCannotStandInForCreativeApproval() {
        InMemoryDesignSessions sessions = new InMemoryDesignSessions();
        DesignSession session = sessions.getOrCreate(8L, "poster");
        session.artifactRef = "design-1";
        session.creativeGate = ReviewGate.PENDING;
        sessions.update(session);
        AtomicInteger calls = new AtomicInteger();

        DesignController.Outcome result = new DesignController(sessions).run(null,
                new Task(8L, "poster"), "export as png", Cancellation.NONE,
                (request, cancellation) -> {
                    calls.incrementAndGet();
                    return ToolResult.okText("should not run");
                });

        assertTrue(result.answer.contains("Creative review is still pending"));
        assertEquals(0, calls.get());
    }

    @Test
    public void rejectionDoesNotApproveSafetyOrFinalization() {
        InMemoryDesignSessions sessions = new InMemoryDesignSessions();
        DesignSession session = sessions.getOrCreate(9L, "poster");
        session.previewRef = "preview://1";
        sessions.update(session);

        DesignController.Outcome result = new DesignController(sessions).run(null,
                new Task(9L, "poster"), "reject this draft", Cancellation.NONE,
                (request, cancellation) -> ToolResult.fail("unused"));

        assertEquals(ReviewGate.REJECTED, result.session.creativeGate);
        assertEquals(ReviewGate.PENDING, result.session.safetyGate);
        assertEquals(ReviewGate.NOT_REQUIRED, result.session.finalizationGate);
    }

    private static ExecutionIdentity identity(Task task, ToolRequest request, int slot) {
        return ExecutionIdentity.of(task.id(), task.runId(), "design." + request.action(), slot,
                "design", request.action(), request.params());
    }
}
