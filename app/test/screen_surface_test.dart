import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/browser/browser_tab.dart';
import 'package:mrnobody/browser/tab_manager.dart';
import 'package:mrnobody/screens/ai_provider_screen.dart';
import 'package:mrnobody/screens/clear_data_screen.dart';
import 'package:mrnobody/screens/downloads_screen.dart';
import 'package:mrnobody/screens/home_screen.dart';
import 'package:mrnobody/screens/privacy_screen.dart';
import 'package:mrnobody/screens/settings_screen.dart';
import 'package:mrnobody/screens/tabs_screen.dart';
import 'package:mrnobody/screens/tasks_screen.dart';
import 'package:mrnobody/theme/app_theme.dart';

import 'fake_browser_engine.dart';

/// Every screen must be able to stand on its own as a pushed route.
///
/// Screens in this app are used two ways: as a page inside the shell's
/// `Scaffold`, and pushed on their own with `Navigator.push`. Only the first
/// supplies a `Material` ancestor, and `MaterialApp` styles any text without
/// one using its debug fallback — red monospace with a yellow-green double
/// underline. Most of that style is overridden by the app's own typography,
/// but the decoration survives, which is exactly what the user saw: green
/// underlines all over Settings, reached from the browser menu (a push) rather
/// than the bottom bar (the shell).
///
/// This is a fitness test, not a golden: it fails on the *cause* (no Material
/// ancestor) rather than on a changed pixel, so it keeps holding as the design
/// moves.
void main() {
  /// The decoration colour `MaterialApp` uses for un-Material'd text.
  const errorDecorationColor = Color(0xFFFFFF00);

  setUpAll(() {
    TestWidgetsFlutterBinding.ensureInitialized();
    _mockCore();
    BrowserTab.engineFactory =
        ({required int tabId, required String url, required bool isPrivate}) =>
            FakeBrowserEngine(initialUrl: url, isPrivate: isPrivate);
  });

  tearDownAll(() {
    BrowserTab.engineFactory = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('mrnobody/core'), null);
  });

  /// Pump a screen with no Scaffold above it — the pushed-route case.
  Future<void> pumpBare(WidgetTester tester, Widget screen) async {
    await tester.pumpWidget(
      MaterialApp(
        debugShowCheckedModeBanner: false,
        theme: AppTheme.dark(),
        home: screen,
      ),
    );
    await tester.pump(const Duration(milliseconds: 300));
  }

  void expectNoDebugTextStyle(WidgetTester tester, String screen) {
    final offenders = <String>[];
    for (final richText in tester.widgetList<RichText>(find.byType(RichText))) {
      final style = richText.text.style;
      if (style == null) continue;
      final flagged = style.decorationColor == errorDecorationColor ||
          style.decorationStyle == TextDecorationStyle.double;
      if (flagged) {
        offenders.add(richText.text.toPlainText());
      }
    }
    expect(
      offenders,
      isEmpty,
      reason: '$screen renders text with no Material ancestor, so MaterialApp '
          'applies its debug style (the yellow-green double underline). '
          'Wrap the screen in ScreenSurface. Offending text: $offenders',
    );
  }

  /// A screen must also actually paint its own background, or a pushed route
  /// shows through to whatever is behind it.
  void expectOwnSurface(WidgetTester tester, String screen) {
    expect(
      find.descendant(of: find.byType(MaterialApp), matching: find.byType(Material)),
      findsWidgets,
      reason: '$screen has no Material of its own when pushed as a route.',
    );
  }

  final screens = <String, Widget Function()>{
    'Settings': () => const SettingsScreen(),
    'Home': () => HomeScreen(
        isActive: true, onSubmit: (_) {}, onOpenTask: (_) {}, onShortcut: (_) {}),
    'Tasks': () => TasksScreen(isActive: true, onOpenTask: (_) {}),
    'Tabs': () => TabsScreen(tabs: TabManager()..newTab(), onOpenTab: () {}),
    'Downloads': () => const DownloadsScreen(),
    'Privacy': () => const PrivacyScreen(),
    'Clear data': () => const ClearDataScreen(),
    'AI provider': () => const AiProviderScreen(initialProvider: 'local'),
  };

  screens.forEach((name, build) {
    testWidgets('$name survives being pushed without a Scaffold', (tester) async {
      await pumpBare(tester, build());
      expectNoDebugTextStyle(tester, name);
      expectOwnSurface(tester, name);
    });
  });
}

void _mockCore() {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
    const MethodChannel('mrnobody/core'),
    (call) async {
      switch (call.method) {
        case 'isFirstLaunchDone':
          return true;
        case 'isHistoryEnabled':
          return false;
        case 'getSettings':
          return {
            'history': false,
            'js': true,
            'suggestions': false,
            'terminal': false,
            'profile': 'BALANCED',
            'searchEngine': 'https://duckduckgo.com/?q=',
            'provider': 'local',
          };
        case 'privacyStats':
          return {
            'pageAds': 0,
            'pageTrackers': 0,
            'todayAds': 0,
            'todayTrackers': 0,
            'score': 100,
          };
        case 'downloadFolder':
          return {'label': 'Movies', 'custom': true};
        case 'downloads':
          return <Map<String, dynamic>>[];
        case 'networkStatus':
          return {
            'transport': 'wifi',
            'metered': false,
            'downKbps': 40000,
            'upKbps': 8000,
            'online': true,
          };
        case 'recentTasks':
        case 'bookmarks':
          return <Map<String, dynamic>>[];
        case 'providerConfig':
          return {'provider': 'local', 'model': '', 'hasKey': false};
        case 'debugLog':
          return <String>[];
        default:
          return null;
      }
    },
  );
}
