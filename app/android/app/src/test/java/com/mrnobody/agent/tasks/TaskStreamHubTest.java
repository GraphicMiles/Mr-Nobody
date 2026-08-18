package com.mrnobody.agent.tasks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The seam between the worker and the UI. Its only job is to route a task's
 * tokens to whoever is listening right now, and to drop them when nobody is —
 * the finished answer is also persisted, so a dropped token is never a lost
 * one, only an unreplayed one.
 */
public class TaskStreamHubTest {

    private static final class Recorder implements TaskStreamHub.Listener {
        final List<String> log = new ArrayList<>();

        @Override public void onToken(long taskId, String token) {
            log.add("token:" + taskId + ":" + token);
        }
        @Override public void onDone(long taskId, String fullText) {
            log.add("done:" + taskId + ":" + fullText);
        }
        @Override public void onError(long taskId, String error) {
            log.add("error:" + taskId + ":" + error);
        }
    }

    @Test
    public void tokensRouteToTheSubscribedTaskOnly() {
        Recorder a = new Recorder();
        Recorder b = new Recorder();
        TaskStreamHub hub = TaskStreamHub.instance();
        hub.subscribe(1, a);
        hub.subscribe(2, b);

        hub.emitToken(1, "hello");

        assertEquals(List.of("token:1:hello"), a.log);
        assertEquals(List.of(), b.log);
    }

    @Test
    public void anUnsubscribedTaskDropsItsTokens() {
        TaskStreamHub hub = TaskStreamHub.instance();
        hub.emitToken(99, "nobody listens");
        // No listener, no throw — the token is simply not replayed.
    }

    @Test
    public void unsubscribingStopsDelivery() {
        Recorder a = new Recorder();
        TaskStreamHub hub = TaskStreamHub.instance();
        hub.subscribe(3, a);
        hub.unsubscribe(3, a);

        hub.emitToken(3, "late");
        hub.emitDone(3, "late");

        assertEquals(List.of(), a.log);
    }

    @Test
    public void doneAndErrorAreDeliveredAsDistinctEvents() {
        Recorder a = new Recorder();
        TaskStreamHub hub = TaskStreamHub.instance();
        hub.subscribe(4, a);

        hub.emitDone(4, "full answer");
        hub.emitError(4, "boom");

        assertEquals(List.of("done:4:full answer", "error:4:boom"), a.log);
    }
}
