package com.mrnobody.agent.planner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LoginWallTest {

    @Test
    public void aSignInPageIsRecognised() {
        assertTrue(LoginWall.isLogin("Please sign in to continue to your account."));
    }

    @Test
    public void anArticleIsNotALoginWall() {
        assertFalse(LoginWall.isLogin("The minister signed the bill into law today."));
    }
}
