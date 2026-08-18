package com.mrnobody.agent.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PageFormsTest {

    @Test
    public void aDownloadFormIsPreferredOverSearch() {
        String html = "<form id='search'><input name='s' value=''></form>"
                + "<form action='/get' method='post'>"
                + "<input type='hidden' name='op' value='download2'>"
                + "<input type='hidden' name='id' value='abc'>"
                + "<input type='submit' name='method_free' value='Free'>"
                + "</form>";
        List<PageForms.Form> forms = PageForms.parse(html, "https://host.com/f");
        assertEquals(2, forms.size());
        PageForms.Form pick = PageForms.pick(forms);
        assertNotNull(pick);
        assertEquals("download2", pick.fields.get("op"));
        assertTrue(pick.action.contains("host.com"));
    }
}
