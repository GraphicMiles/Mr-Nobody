#!/usr/bin/env python3
"""Structural regression checks for the ten full-screen tutorial concepts."""
from html.parser import HTMLParser
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
DIR = ROOT / "website" / "tutorials"
VARIANTS = sorted(DIR.glob("[0-9][0-9]-*.html"))

class Parser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids=[]; self.slides=0; self.links=[]; self.body_classes=[]; self.body_concept=None
    def handle_starttag(self, tag, attrs):
        data=dict(attrs)
        if data.get("id"): self.ids.append(data["id"])
        if tag=="section" and "slide" in (data.get("class") or "").split(): self.slides+=1
        if tag=="a" and data.get("href"): self.links.append(data["href"])
        if tag=="body":
            self.body_classes=(data.get("class") or "").split()
            self.body_concept=data.get("data-concept")

class TutorialVariantsTest(unittest.TestCase):
    def test_exactly_ten_variants_exist(self):
        self.assertEqual(len(VARIANTS),10)
        self.assertEqual([p.name[:2] for p in VARIANTS],[f"{i:02d}" for i in range(1,11)])

    def test_gallery_links_every_variant(self):
        parser=Parser(); parser.feed((DIR/"index.html").read_text(encoding="utf-8"))
        linked={Path(h).name for h in parser.links if re.match(r"\d\d-",Path(h).name)}
        self.assertEqual(linked,{p.name for p in VARIANTS})

    def test_each_variant_is_complete_full_screen_tutorial(self):
        forbidden=('id="prev"','id="play"','id="reset"','preview controls','class="controls"')
        for path in VARIANTS:
            with self.subTest(path=path.name):
                html=path.read_text(encoding="utf-8")
                parser=Parser(); parser.feed(html)
                self.assertEqual(parser.slides,5)
                self.assertEqual(len(parser.ids),len(set(parser.ids)))
                self.assertIn(parser.body_concept,parser.body_classes)
                self.assertTrue(html.rstrip().lower().endswith("</html>"))
                self.assertEqual(html.lower().count("</html>"),1)
                self.assertIn('vendor/motion-13.1.1.js',html)
                self.assertIn('vendor/matter-0.20.0.min.js',html)
                self.assertIn('vendor/anime-4.5.0.min.js',html)
                for token in forbidden: self.assertNotIn(token,html)

    def test_runtime_and_vendor_assets_are_local(self):
        required=["tutorial.css","tutorial.js","vendor/motion-13.1.1.js","vendor/matter-0.20.0.min.js","vendor/anime-4.5.0.min.js","vendor/licenses/MOTION-LICENSE.txt","vendor/licenses/MATTER-LICENSE.txt","vendor/licenses/ANIME-LICENSE.txt"]
        for rel in required:
            with self.subTest(asset=rel): self.assertTrue((DIR/rel).is_file())

    def test_css_uses_dynamic_viewport_and_reduced_motion(self):
        css=(DIR/"tutorial.css").read_text(encoding="utf-8")
        self.assertIn("height:100dvh",css)
        self.assertIn("prefers-reduced-motion:reduce",css)
        self.assertNotIn(".controls",css)

    def test_home_motion_lab_runs_all_ten_sequences_safely(self):
        html=(DIR/"home-motion-lab.html").read_text(encoding="utf-8")
        js=(DIR/"home-motion-lab.js").read_text(encoding="utf-8")
        css=(DIR/"home-motion-lab.css").read_text(encoding="utf-8")
        parser=Parser(); parser.feed(html)
        self.assertEqual(len(parser.ids),len(set(parser.ids)))
        self.assertIn('home-motion-lab.html',(DIR/"index.html").read_text(encoding="utf-8"))
        match=re.search(r"var sequences=\[([^]]+)\]",js)
        self.assertIsNotNone(match)
        self.assertEqual(len(re.findall(r"'[^']+'",match.group(1))),10)
        self.assertIn("visibilitychange",js)
        self.assertIn("prefers-reduced-motion: reduce",js)
        self.assertIn("height:100dvh",css)
        self.assertNotIn('class="controls"',html)
        for removed in ('class="appbar"','HOME MOTION LAB','id="sequenceName"','TAP TO SWITCH'):
            self.assertNotIn(removed,html)
        self.assertNotIn("setProperty('--accent'",js)
        self.assertNotIn('var palettes=',js)

if __name__ == "__main__": unittest.main()
