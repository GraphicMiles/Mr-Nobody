import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/browser/browser_tab.dart';
import 'package:mrnobody/browser/tab_manager.dart';
import 'package:mrnobody/theme/app_theme.dart';
import 'package:mrnobody/widgets/toast.dart';

import 'fake_browser_engine.dart';

/// Regressions for state that the UI reads but nothing was updating.
void main() {
  setUpAll(() {
    BrowserTab.engineFactory = ({required int tabId, required String url, required bool isPrivate}) =>
        FakeBrowserEngine(initialUrl: url, isPrivate: isPrivate);
  });
  tearDownAll(() => BrowserTab.engineFactory = null);

  group('BrowserTab keeps its own state', () {
    test('a navigation updates url, host and label', () {
      final tab = BrowserTab(1);
      expect(tab.label, 'New tab');

      tab.engine.onUrlChanged!('https://duckduckgo.com/?q=youtube');

      expect(tab.url, 'https://duckduckgo.com/?q=youtube');
      expect(tab.host, 'duckduckgo.com');
      expect(tab.label, 'duckduckgo.com');
      expect(tab.isSecure, isTrue, reason: 'the padlock reads this');
    });

    test('the page title wins, and is dropped when the document changes', () {
      final tab = BrowserTab(1);
      tab.engine.onUrlChanged!('https://example.com');
      tab.engine.onTitleChanged!('Example Domain');
      expect(tab.label, 'Example Domain');

      tab.engine.onUrlChanged!('https://example.com/other');
      expect(tab.label, 'example.com',
          reason: 'a stale title must not describe the new document');
    });

    test('listeners are notified, so tab cards repaint', () {
      final tab = BrowserTab(1);
      var notifications = 0;
      tab.addListener(() => notifications++);

      tab.engine.onUrlChanged!('https://example.com');
      tab.engine.onTitleChanged!('Example Domain');

      expect(notifications, 2);
    });

    test('scrolling down hides the chrome, scrolling up brings it back', () {
      final tab = BrowserTab(1);
      expect(tab.chromeVisible.value, isTrue);

      tab.engine.onScroll!(400); // scrolled down
      expect(tab.chromeVisible.value, isFalse);

      tab.engine.onScroll!(200); // scrolled back up
      expect(tab.chromeVisible.value, isTrue);

      tab.engine.onScroll!(600);
      expect(tab.chromeVisible.value, isFalse);
      tab.engine.onScroll!(0); // back at the top
      expect(tab.chromeVisible.value, isTrue);
    });

    test('a tiny scroll jitter does not flap the chrome', () {
      final tab = BrowserTab(1);
      tab.engine.onScroll!(5);
      expect(tab.chromeVisible.value, isTrue);
    });
  });

  test('closing a tab disposes it exactly once', () {
    final tabs = TabManager();
    tabs.newTab();
    tabs.newTab();
    expect(tabs.length, 2);

    tabs.close(0);
    tabs.closeAll();

    expect(tabs.length, 0);
    expect(tabs.active, isNull);
  });

  test('clearing browser data closes private tabs but keeps normal tabs', () {
    final tabs = TabManager();
    final normal = tabs.newTab(url: 'https://example.com');
    tabs.newTab(isPrivate: true, url: 'https://httpbin.org/cookies');

    tabs.closePrivateTabs();

    expect(tabs.length, 1);
    expect(tabs.active?.id, normal.id);
    expect(tabs.active?.url, 'https://example.com');
    tabs.closeAll();
  });

  testWidgets('toast text has a Material ancestor', (tester) async {
    // Without one, the framework paints its yellow/black "missing Material"
    // underline under the label — which is what the coloured line under every
    // toast actually was.
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.dark(),
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: ElevatedButton(
                onPressed: () => AppToast.show(context, 'No bookmarks'),
                child: const Text('show'),
              ),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('show'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));

    final toast = find.text('No bookmarks');
    expect(toast, findsOneWidget);
    expect(
      find.ancestor(of: toast, matching: find.byType(Material)),
      findsWidgets,
      reason: 'overlay content must bring its own Material',
    );

    AppToast.dismiss();
    await tester.pump();
  });
}
