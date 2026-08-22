package com.mrnobody.agent.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CanvaEditPlanTest {
    @Test
    public void exactQuotedReplacementSelectsTheMatchingElement() throws Exception {
        JSONObject opened = opened();
        CanvaEditPlan plan = CanvaEditPlan.from(opened,
                "change 'Old headline' to 'Summer Sale'");
        assertEquals("tx-1", plan.transactionId);
        assertEquals("element-2", plan.elementId);
        assertEquals("Old headline", plan.oldText);
        assertEquals("Summer Sale", plan.newText);
        assertTrue(plan.responsive);
    }

    @Test
    public void vagueAestheticEditFailsClosed() throws Exception {
        assertNull(CanvaEditPlan.from(opened(), "make it pop more"));
    }

    private static JSONObject opened() throws Exception {
        JSONArray richtexts = new JSONArray()
                .put(rich("element-1", "Small copy"))
                .put(rich("element-2", "Old headline"));
        return new JSONObject()
                .put("transaction", new JSONObject().put("transaction_id", "tx-1"))
                .put("richtexts", richtexts)
                .put("pages", new JSONArray().put(new JSONObject()
                        .put("page_number", 1).put("is_responsive", true)));
    }

    private static JSONObject rich(String id, String text) throws Exception {
        return new JSONObject().put("element_id", id).put("page_index", 1)
                .put("regions", new JSONArray().put(new JSONObject().put("text", text)));
    }
}
