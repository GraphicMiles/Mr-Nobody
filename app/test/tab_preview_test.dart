import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/browser/browser_tab.dart';
import 'package:mrnobody/browser/tab_manager.dart';
import 'package:mrnobody/screens/tabs_screen.dart';
import 'package:mrnobody/theme/app_theme.dart';

import 'fake_browser_engine.dart';
import 'test_fonts.dart';

/// A tab card used to draw two grey lines whatever the tab contained — the
/// same picture for a search, a video and an empty tab. It now shows the page.
void main() {
  setUpAll(loadTestFonts);

  late FakeBrowserEngine lastEngine;

  setUp(() {
    BrowserTab.engineFactory = ({required int tabId, required String url, required bool isPrivate}) {
      lastEngine = FakeBrowserEngine(initialUrl: url, isPrivate: isPrivate);
      return lastEngine;
    };
  });

  tearDown(() => BrowserTab.engineFactory = null);

  /// A 1x1 JPEG — enough for Image.memory to decode.
  final tinyJpeg = Uint8List.fromList([
    0xFF, 0xD8, 0xFF, 0xDB, 0x00, 0x43, 0x00, ...List.filled(64, 0x08), //
    0xFF, 0xC9, 0x00, 0x0B, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
    0xFF, 0xCC, 0x00, 0x06, 0x00, 0x10, 0x10, 0x05,
    0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0xD2, 0xCF, 0x20,
    0xFF, 0xD9,
  ]);

  Future<void> pumpGrid(WidgetTester tester, TabManager tabs) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.dark(),
      home: Scaffold(
        backgroundColor: AppColors.bg,
        body: TabsScreen(tabs: tabs, onOpenTab: () {}),
      ),
    ));
    await tester.pump();
  }

  testWidgets('a tab with no picture yet falls back to the placeholder', (tester) async {
    final tabs = TabManager();
    tabs.newTab(url: 'https://example.com');

    await pumpGrid(tester, tabs);

    expect(find.byType(Image), findsNothing);
    expect(find.text('example.com'), findsOneWidget);
  });

  testWidgets('a captured page is drawn on its card', (tester) async {
    final tabs = TabManager();
    final tab = tabs.newTab(url: 'https://example.com');
    lastEngine.thumbnail = tinyJpeg;

    await tab.captureThumbnail();
    await pumpGrid(tester, tabs);

    expect(find.byType(Image), findsOneWidget);
  });

  testWidgets('a private tab is never photographed', (tester) async {
    final tabs = TabManager();
    final tab = tabs.newTab(url: 'https://example.com', isPrivate: true);
    lastEngine.thumbnail = tinyJpeg;

    await tab.captureThumbnail();

    expect(tab.thumbnail, isNull,
        reason: 'a thumbnail is a picture of what someone was reading');
    await pumpGrid(tester, tabs);
    expect(find.byType(Image), findsNothing);
    expect(find.text('PRIVATE'), findsOneWidget);
  });

  test('a capture updates listeners so the grid repaints', () async {
    final tabs = TabManager();
    final tab = tabs.newTab(url: 'https://example.com');
    lastEngine.thumbnail = tinyJpeg;

    var notifications = 0;
    tab.addListener(() => notifications++);
    await tab.captureThumbnail();

    expect(tab.thumbnail, isNotNull);
    expect(notifications, 1);
  });

  test('an empty capture leaves the previous picture alone', () async {
    final tabs = TabManager();
    final tab = tabs.newTab(url: 'https://example.com');
    lastEngine.thumbnail = tinyJpeg;
    await tab.captureThumbnail();

    lastEngine.thumbnail = Uint8List(0);
    await tab.captureThumbnail();

    expect(tab.thumbnail, isNotNull, reason: 'a failed capture must not blank the card');
  });

  test('closing a tab drops its picture', () {
    final tabs = TabManager();
    final tab = tabs.newTab(url: 'https://example.com');
    tab.thumbnail = tinyJpeg;

    tabs.close(0);

    expect(tab.thumbnail, isNull);
  });
}
