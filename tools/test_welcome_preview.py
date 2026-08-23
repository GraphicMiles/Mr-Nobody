#!/usr/bin/env python3
"""Structural regression checks for the standalone first-run tutorial preview."""

from html.parser import HTMLParser
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "website" / "welcome_preview.html"


class PreviewParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.ids: list[str] = []
        self.slide_count = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.append(values["id"] or "")
        classes = (values.get("class") or "").split()
        if tag == "section" and "slide" in classes:
            self.slide_count += 1


class WelcomePreviewTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.html = PREVIEW.read_text(encoding="utf-8")
        cls.parser = PreviewParser()
        cls.parser.feed(cls.html)

    def test_is_one_complete_document_with_no_trailing_fragment(self) -> None:
        self.assertEqual(self.html.lower().count("<html"), 1)
        self.assertEqual(self.html.lower().count("</html>"), 1)
        self.assertEqual(self.html.lower().count("<script"), 1)
        self.assertEqual(self.html.lower().count("</script>"), 1)
        self.assertTrue(self.html.rstrip().lower().endswith("</html>"))

    def test_ids_are_unique(self) -> None:
        self.assertEqual(len(self.parser.ids), len(set(self.parser.ids)))

    def test_slide_count_matches_script_and_progress(self) -> None:
        total = re.search(r"var TOTAL = (\d+);", self.html)
        self.assertIsNotNone(total)
        self.assertEqual(self.parser.slide_count, int(total.group(1)))
        self.assertIn(f"1 / {self.parser.slide_count}", self.html)

    def test_logo_targets_match_their_slide_indexes(self) -> None:
        for index in (0, 6):
            for prefix in ("ph", "sh", "du", "g1", "g2", "g3"):
                self.assertIn(f"{prefix}{index}", self.parser.ids)

    def test_reset_guards_the_previous_slide(self) -> None:
        self.assertIn("if (slides[idx]) slides[idx].classList.remove('active');", self.html)


if __name__ == "__main__":
    unittest.main()
