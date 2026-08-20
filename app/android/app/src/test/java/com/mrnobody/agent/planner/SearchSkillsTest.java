package com.mrnobody.agent.planner;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SearchSkillsTest {

    @Test
    public void latestYouTubeWinsOverGenericFreshness() {
        SearchSkills.Skill skill = SearchSkills.route(
                "latest video on youtube from Screen Crush channel");
        assertEquals(SearchSkills.Kind.LATEST_YOUTUBE, skill.kind);
        assertTrue(skill.listingOnly);
        assertEquals("youtube", skill.provider);
        assertTrue(skill.query.contains("youtube"));
    }

    @Test
    public void ordinaryYouTubeSearchIsSiteRestricted() {
        SearchSkills.Skill skill = SearchSkills.route(
                "search youtube for Lagos travel videos");
        assertEquals(SearchSkills.Kind.YOUTUBE, skill.kind);
        assertEquals("youtube", skill.provider);
        assertTrue(skill.query.contains("youtube"));
        assertFalse(skill.query.toLowerCase().contains("facebook"));
    }

    @Test
    public void facebookSkillPromisesPublicResultsOnly() {
        SearchSkills.Skill skill = SearchSkills.route(
                "find the public Facebook page for Lagos State");
        assertEquals(SearchSkills.Kind.FACEBOOK_PUBLIC, skill.kind);
        assertTrue(skill.query, skill.query.startsWith("Lagos State"));
        assertTrue(skill.query.contains("site:facebook.com"));
        assertTrue(skill.note.toLowerCase().contains("private"));
    }

    @Test
    public void materialSearchUsesGoogleOnlyWhenExplicitlyAsked() {
        SearchSkills.Skill google = SearchSkills.route(
                "find a calculus PDF material using Google search");
        assertEquals(SearchSkills.Kind.MATERIAL, google.kind);
        assertEquals("google", google.provider);
        assertTrue(google.query.contains("filetype:pdf"));

        SearchSkills.Skill generic = SearchSkills.route(
                "find a calculus PDF material");
        assertEquals(SearchSkills.Kind.MATERIAL, generic.kind);
        assertEquals("", generic.provider);
    }

    @Test
    public void latestFinancialInformationUsesTheMoreSpecificFreshRoute() {
        SearchSkills.Skill skill = SearchSkills.route("latest Nigeria inflation rate");
        assertEquals(SearchSkills.Kind.FINANCE, skill.kind);
        String year = new java.text.SimpleDateFormat("yyyy", java.util.Locale.US)
                .format(new java.util.Date());
        assertTrue(skill.query.contains(year));
        assertFalse(skill.listingOnly);
    }

    @Test
    public void genericLatestInformationStillGetsCurrentYearBias() {
        SearchSkills.Skill skill = SearchSkills.route("latest Mars mission update");
        assertEquals(SearchSkills.Kind.FRESH_INFORMATION, skill.kind);
        String year = new java.text.SimpleDateFormat("yyyy", java.util.Locale.US)
                .format(new java.util.Date());
        assertTrue(skill.query.contains(year));
    }

    @Test
    public void constrainedSkillDoesNotFallBackToUnrelatedHosts() {
        SearchSkills.Skill skill = SearchSkills.route("search facebook for Lagos State");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "Unrelated");
        row.put("url", "https://example.com/lagos");
        rows.add(row);
        assertTrue(skill.filter(rows).isEmpty());
    }

    @Test
    public void academicPaperBeatsGenericMaterialAndFreshness() {
        SearchSkills.Skill skill = SearchSkills.route(
                "find the latest research paper on malaria vaccines");
        assertEquals(SearchSkills.Kind.ACADEMIC, skill.kind);
        assertTrue(skill.listingOnly);
        assertTrue(skill.query.contains("arxiv.org"));
    }

    @Test
    public void officialDocumentationGetsItsOwnReadableRoute() {
        SearchSkills.Skill skill = SearchSkills.route(
                "find the official documentation for the Android CameraX API");
        assertEquals(SearchSkills.Kind.OFFICIAL_DOCUMENTATION, skill.kind);
        assertFalse(skill.listingOnly);
        assertTrue(skill.query.contains("official documentation"));
    }

    @Test
    public void factCheckAddsPrimaryEvidenceLanguage() {
        SearchSkills.Skill skill = SearchSkills.route(
                "fact check the claim that the moon is made of cheese");
        assertEquals(SearchSkills.Kind.FACT_CHECK, skill.kind);
        assertTrue(skill.query.contains("primary evidence"));
    }

    @Test
    public void weatherAndFinanceAreTimeBiasedBeforeGenericLatest() {
        SearchSkills.Skill weather = SearchSkills.route("Lagos weather today");
        SearchSkills.Skill finance = SearchSkills.route("current bitcoin price");
        assertEquals(SearchSkills.Kind.WEATHER, weather.kind);
        assertEquals(SearchSkills.Kind.FINANCE, finance.kind);
        String year = new java.text.SimpleDateFormat("yyyy", java.util.Locale.US)
                .format(new java.util.Date());
        assertTrue(weather.query.contains(year));
        assertTrue(finance.query.contains(year));
    }

    @Test
    public void newsAndGovernmentHaveDistinctEvidenceRoutes() {
        assertEquals(SearchSkills.Kind.NEWS,
                SearchSkills.route("latest Nigeria technology news").kind);
        assertEquals(SearchSkills.Kind.GOVERNMENT,
                SearchSkills.route("Nigeria census data from an official source").kind);
    }

    @Test
    public void listingAnswerContainsLinksWithoutPageCode() {
        SearchSkills.Skill skill = SearchSkills.route("search youtube for privacy videos");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "Privacy Explained");
        row.put("url", "https://youtube.com/watch?v=abc");
        row.put("snippet", "A public video result.");
        rows.add(row);
        String answer = skill.listingAnswer(rows);
        assertTrue(answer.contains("Privacy Explained"));
        assertTrue(answer.contains("https://youtube.com/watch?v=abc"));
        assertFalse(answer.contains("EXPERIMENT_FLAGS"));
    }

    @Test
    public void unknownRequestFallsBackToGeneralResearch() {
        assertEquals(SearchSkills.Kind.GENERIC,
                SearchSkills.route("why is the sky blue").kind);
    }
}
