import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/browser/browser_tab.dart';
import 'package:mrnobody/browser/tab_manager.dart';
import 'package:mrnobody/screens/tabs_screen.dart';
import 'package:mrnobody/theme/app_theme.dart';

import 'fake_browser_engine.dart';

void main() {
  late TabManager tabs;

  setUp(() {
    BrowserTab.engineFactory =
        ({required int tabId, required String url, required bool isPrivate}) =>
            FakeBrowserEngine(initialUrl: url, isPrivate: isPrivate);
    tabs = TabManager();
    tabs.newTab(url: 'https://old.example');
  });

  tearDown(() {
    tabs.closeAll();
    BrowserTab.engineFactory = null;
  });

  testWidgets('template card creates, selects and opens a new tab', (tester) async {
    var opened = 0;
    final oldId = tabs.active!.id;
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.dark(),
      home: TabsScreen(
        tabs: tabs,
        onOpenTab: () => opened++,
      ),
    ));
    await tester.pump();

    await tester.tap(find.byKey(const ValueKey('new-tab-card')));
    await tester.pump();

    expect(tabs.length, 2);
    expect(tabs.active!.id, isNot(oldId));
    expect(tabs.active!.url, isEmpty);
    expect(opened, 1, reason: 'the user should not have to open it from the grid manually');
  });
}
