package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TaskArtifactTest {

    private static List<TaskArtifact> three() {
        return Arrays.asList(
                new TaskArtifact(1, "Alpha Laptop", "https://a.example/1", ""),
                new TaskArtifact(2, "Beta Laptop", "https://b.example/2", ""),
                new TaskArtifact(3, "Gamma Laptop", "https://c.example/3", ""));
    }

    @Test
    public void secondOneResolvesByIndex() {
        TaskArtifact a = TaskArtifact.resolve("open the second one", three());
        assertEquals(2, a.index);
        assertTrue(a.url.contains("b.example"));
    }

    @Test
    public void itResolvesToTheLastItem() {
        TaskArtifact a = TaskArtifact.resolve("where can I legally watch it?", three());
        assertEquals(3, a.index);
    }

    @Test
    public void titleFragmentWins() {
        TaskArtifact a = TaskArtifact.resolve("open Beta Laptop", three());
        assertEquals(2, a.index);
    }

    @Test
    public void encodeRoundTrip() {
        String json = TaskArtifact.encode(three());
        List<TaskArtifact> back = TaskArtifact.decode(json);
        assertEquals(3, back.size());
        assertEquals("Alpha Laptop", back.get(0).title);
    }

    @Test
    public void emptyFollowUpIsNull() {
        assertNull(TaskArtifact.resolve("tell me more about laptops in general", three()));
    }

    @Test
    public void imageSurvivesRoundTripAndAttach() {
        TaskArtifact a = new TaskArtifact(1, "The Boys", "https://prime.example/boys",
                "", "https://cdn.example/boys.jpg");
        List<TaskArtifact> back = TaskArtifact.decode(TaskArtifact.encode(java.util.Collections.singletonList(a)));
        assertEquals("https://cdn.example/boys.jpg", back.get(0).image);

        java.util.Map<String, String> images = new java.util.HashMap<>();
        images.put("https://a.example/1", "https://cdn.example/alpha.jpg");
        List<TaskArtifact> attached = TaskArtifact.attachImages(three(), images);
        assertEquals("https://cdn.example/alpha.jpg", attached.get(0).image);
        assertEquals("", attached.get(1).image);
    }
}
