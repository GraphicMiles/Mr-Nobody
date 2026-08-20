import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/browser/browser_engine.dart';
import 'package:mrnobody/browser/browser_tab.dart';
import 'package:mrnobody/browser/tab_manager.dart';
import 'package:mrnobody/screens/browser_screen.dart';
import 'package:mrnobody/theme/app_theme.dart';

import 'fake_browser_engine.dart';

void main() {
  late TabManager tabs;
  late BrowserTab tab;
  late FakeBrowserEngine engine;

  setUp(() {
    BrowserTab.engineFactory =
        ({required int tabId, required String url, required bool isPrivate}) {
      engine = FakeBrowserEngine(initialUrl: url, isPrivate: isPrivate);
      return engine;
    };
    tabs = TabManager();
    tab = tabs.newTab(url: 'https://example.com');
  });

  tearDown(() {
    tabs.closeAll();
    BrowserTab.engineFactory = null;
  });

  Future<void> pumpBrowser(WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.dark(),
      home: BrowserScreen(
        tabs: tabs,
        onShowTabs: () {},
        onOpenDestination: (_) {},
      ),
    ));
    await tester.pump();
  }

  testWidgets('delete icon is visible only while the address field is focused',
      (tester) async {
    await pumpBrowser(tester);
    expect(find.byKey(const ValueKey('address-delete')), findsNothing);

    await tester.tap(find.byType(TextField));
    await tester.pump();
    expect(find.byKey(const ValueKey('address-delete')), findsOneWidget);

    FocusManager.instance.primaryFocus?.unfocus();
    await tester.pump();
    expect(find.byKey(const ValueKey('address-delete')), findsNothing);
  });

  testWidgets('one tap deletes one character and long press offers clear all',
      (tester) async {
    await pumpBrowser(tester);
    await tester.tap(find.byType(TextField));
    await tester.enterText(find.byType(TextField), 'example');

    await tester.tap(find.byKey(const ValueKey('address-delete')));
    await tester.pump();
    TextField field = tester.widget(find.byType(TextField));
    expect(field.controller!.text, 'exampl');

    await tester.longPress(find.byKey(const ValueKey('address-delete')));
    await tester.pumpAndSettle();
    expect(find.text('Clear all'), findsOneWidget);
    await tester.tap(find.text('Clear all'));
    await tester.pumpAndSettle();
    field = tester.widget(find.byType(TextField));
    expect(field.controller!.text, isEmpty);
  });

  testWidgets('refresh becomes a spinner until loading finishes', (tester) async {
    await pumpBrowser(tester);
    expect(find.byKey(const ValueKey('refresh-idle')), findsOneWidget);

    engine.onLoadingChanged?.call(true);
    await tester.pump();
    expect(find.byKey(const ValueKey('refresh-loading')), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('refresh-button')));
    await tester.pump();
    expect(engine.reloaded, isTrue);
    expect(find.byKey(const ValueKey('refresh-loading')), findsOneWidget);

    engine.onLoadingChanged?.call(false);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('refresh-idle')), findsOneWidget);
  });

  testWidgets('harmful download can be rejected', (tester) async {
    await pumpBrowser(tester);
    engine.onDownloadApproval?.call(const BrowserDownloadRequest(
      id: 'danger-1',
      name: 'update.apk',
      mime: 'application/vnd.android.package-archive',
      sourceHost: 'example.com',
      warning: 'This file can install software, run code, or change the device.',
    ));
    await tester.pumpAndSettle();

    expect(find.text('Potentially harmful file'), findsOneWidget);
    await tester.tap(find.text('Reject'));
    await tester.pumpAndSettle();

    expect(engine.resolvedDownloadId, 'danger-1');
    expect(engine.resolvedDownloadAllow, isFalse);
  });

  testWidgets('harmful download can be explicitly allowed', (tester) async {
    await pumpBrowser(tester);
    engine.onDownloadApproval?.call(const BrowserDownloadRequest(
      id: 'danger-2',
      name: 'run.sh',
      mime: 'text/x-shellscript',
      sourceHost: 'example.com',
      warning: 'This file can install software, run code, or change the device.',
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Go ahead'));
    await tester.pumpAndSettle();

    expect(engine.resolvedDownloadId, 'danger-2');
    expect(engine.resolvedDownloadAllow, isTrue);
  });
}
