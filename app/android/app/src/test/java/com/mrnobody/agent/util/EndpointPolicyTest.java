package com.mrnobody.agent.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class EndpointPolicyTest {

    @Test
    public void httpsBaseWithPathIsAllowed() {
        assertNull(EndpointPolicy.secureBaseReason("https://api.example.com/v1"));
    }

    @Test
    public void cleartextAndMalformedEndpointsAreRefused() {
        assertNotNull(EndpointPolicy.secureBaseReason("http://api.example.com/v1"));
        assertNotNull(EndpointPolicy.secureBaseReason("http://127.0.0.1:8080"));
        assertNotNull(EndpointPolicy.secureBaseReason("not a url"));
        assertNotNull(EndpointPolicy.secureBaseReason(""));
    }

    @Test
    public void credentialsQueryAndFragmentsAreNotPartOfABase() {
        assertNotNull(EndpointPolicy.secureBaseReason("https://user:pass@example.com/v1"));
        assertNotNull(EndpointPolicy.secureBaseReason("https://example.com/v1?key=secret"));
        assertNotNull(EndpointPolicy.secureBaseReason("https://example.com/v1#fragment"));
    }
}
