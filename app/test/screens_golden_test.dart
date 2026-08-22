import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/browser/browser_tab.dart';
import 'package:mrnobody/browser/tab_manager.dart';
import 'package:mrnobody/screens/ai_provider_screen.dart';
import 'package:mrnobody/screens/bookmarks_screen.dart';
import 'package:mrnobody/screens/browser_screen.dart';
import 'package:mrnobody/screens/clear_data_screen.dart';
import 'package:mrnobody/screens/downloads_screen.dart';
import 'package:mrnobody/screens/dev_panel_screen.dart';
import 'package:mrnobody/screens/home_screen.dart';
import 'package:mrnobody/screens/launch_screen.dart';
import 'package:mrnobody/screens/memory_screen.dart';
import 'package:mrnobody/screens/privacy_screen.dart';
import 'package:mrnobody/screens/restricted_tools_screen.dart';
import 'package:mrnobody/screens/settings_screen.dart';
import 'package:mrnobody/screens/tabs_screen.dart';
import 'package:mrnobody/screens/task_chat_screen.dart';
import 'package:mrnobody/screens/tasks_screen.dart';
import 'package:mrnobody/state/app_state.dart';
import 'package:mrnobody/theme/app_theme.dart';
import 'package:mrnobody/widgets/bottom_nav.dart';

import 'fake_browser_engine.dart';
import 'test_fonts.dart';

/// Renders every screen at a phone size against a faked core and captures a
/// golden image, so a UI regression against the approved wireframe shows up as
/// a failing test instead of a surprise on someone's phone.
///
/// Refresh with: flutter test --update-goldens
void main() {
  const phone = Size(390, 844);

  setUpAll(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    await loadTestFonts();
    _mockCore();
    // No Android platform view in a widget test.
    BrowserTab.engineFactory = (
            {required int tabId,
            required String url,
            required bool isPrivate}) =>
        FakeBrowserEngine(initialUrl: url, isPrivate: isPrivate);
    // The task chat subscribes to the answer stream. In a golden capture there
    // is no live stream, so register an empty one — an un-mocked EventChannel
    // subscription throws MissingPluginException through FlutterError, which
    // fails the test even though the poll and timed reveal already cover it.
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
      const EventChannel('mrnobody/task-stream'),
      MockStreamHandler.inline(
        onListen: (Object? arguments, MockStreamHandlerEventSink events) {},
      ),
    );
  });

  tearDownAll(() => BrowserTab.engineFactory = null);

  Future<void> pumpScreen(
    WidgetTester tester,
    Widget Function() build,
    String golden, {
    required String themeId,
  }) async {
    tester.view.physicalSize = phone * 3;
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);

    // Set the runtime palette before constructing wrappers whose colours are
    // constructor arguments (for example a destination Scaffold).
    final theme = AppTheme.forTheme(themeId);
    final child = build();
    await tester.pumpWidget(
      MaterialApp(
        debugShowCheckedModeBanner: false,
        theme: theme,
        home: child,
      ),
    );
    await tester.pump(const Duration(milliseconds: 400));
    await expectLater(
      find.byType(MaterialApp),
      matchesGoldenFile('goldens/$golden.png'),
    );
  }

  final cases = <_GoldenCase>[
    _GoldenCase(
      'S1 launch',
      's1_launch',
      () => LaunchScreen(onStart: () {}, onPrivacy: () {}),
    ),
    _GoldenCase(
      'S2 agent home',
      's2_home',
      () => Scaffold(
        backgroundColor: AppColors.bg,
        body: HomeScreen(
          onSubmit: (_) {},
          onShortcut: (_) {},
          onOpenTask: (_) {},
        ),
        bottomNavigationBar:
            BottomNav(selected: 0, onSelect: (_) {}, onNew: () {}),
      ),
    ),
    _GoldenCase(
      'S2 browser',
      's2_browser',
      () {
        final tabs = TabManager();
        tabs.newTab(url: 'https://example.com');
        return BrowserScreen(
          tabs: tabs,
          onShowTabs: () {},
          onOpenDestination: (_) {},
        );
      },
    ),
    _GoldenCase(
      'S3 tabs',
      's3_tabs',
      () {
        final tabs = TabManager();
        tabs.newTab(url: 'https://example.com');
        tabs.newTab(url: 'https://duckduckgo.com/?q=arsenal');
        tabs.newTab(isPrivate: true, url: 'https://news.ycombinator.com');
        return Scaffold(
          backgroundColor: AppColors.bg,
          body: TabsScreen(tabs: tabs, onOpenTab: () {}),
          bottomNavigationBar:
              BottomNav(selected: 1, onSelect: (_) {}, onNew: () {}),
        );
      },
    ),
    _GoldenCase('S4 privacy', 's4_privacy', () => const PrivacyScreen()),
    _GoldenCase(
      'S5 tasks',
      's5_tasks',
      () => Scaffold(
        backgroundColor: AppColors.bg,
        body: TasksScreen(onOpenTask: (_) {}),
        bottomNavigationBar:
            BottomNav(selected: 2, onSelect: (_) {}, onNew: () {}),
      ),
    ),
    _GoldenCase(
      'S5 task detail',
      's5_task_detail',
      () => const TaskChatScreen(
        taskId: 1,
        title: 'Find laptops under 500000',
        instruction: 'Find laptops under 500000',
      ),
    ),
    _GoldenCase(
      'S6 settings',
      's6_settings',
      () => Scaffold(
        backgroundColor: AppColors.bg,
        body: const SettingsScreen(),
        bottomNavigationBar:
            BottomNav(selected: 3, onSelect: (_) {}, onNew: () {}),
      ),
      loadsSettings: true,
    ),
    _GoldenCase(
      'S6 AI provider',
      's6_ai_provider',
      () => const AiProviderScreen(initialProvider: 'groq'),
    ),
    _GoldenCase(
      'S7 clear data',
      's7_clear_data',
      () => const ClearDataScreen(),
    ),
    _GoldenCase(
      'S8 downloads',
      's8_downloads',
      () => const DownloadsScreen(),
    ),
    _GoldenCase(
      'S9 bookmarks',
      's9_bookmarks',
      () => BookmarksScreen(onOpenUrl: (_) {}),
    ),
    _GoldenCase(
      'S10 restricted tools',
      's10_restricted_tools',
      () => const RestrictedToolsScreen(),
    ),
    _GoldenCase(
      'S11 developer panel',
      's11_developer_panel',
      () => const DevPanelScreen(),
    ),
    _GoldenCase(
      'S12 memory',
      's12_memory',
      () => const MemoryScreen(),
    ),
  ];

  for (final themeId in const [AppColors.classicId, AppColors.warmId]) {
    final prefix = themeId == AppColors.warmId ? 'warm_' : '';
    group(themeId == AppColors.warmId ? 'Warm cream' : 'Classic dark', () {
      for (final screen in cases) {
        testWidgets(screen.name, (tester) async {
          _mockTheme = themeId;
          if (screen.loadsSettings) await AppState.instance.load();
          await pumpScreen(
            tester,
            screen.build,
            '$prefix${screen.file}',
            themeId: themeId,
          );
        });
      }
    });
  }
}

class _GoldenCase {
  final String name;
  final String file;
  final Widget Function() build;
  final bool loadsSettings;

  const _GoldenCase(
    this.name,
    this.file,
    this.build, {
    this.loadsSettings = false,
  });
}

String _mockTheme = AppColors.classicId;

/// A stand-in for the Java core, so screens render with representative data.
void _mockCore() {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(
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
            'fingerprint': false,
            'profile': 'BALANCED',
            'searchEngine': 'https://duckduckgo.com/?q=',
            'provider': 'local',
            'theme': _mockTheme,
            'resourcePolicy': 'OFF',
          };
        case 'engineInfo':
          return {
            'engine': 'Android System WebView',
            'multiProfile': true,
            'documentStartScript': true,
            'proxyOverride': true,
          };
        case 'privacyStats':
          return {
            'pageAds': 8,
            'pageTrackers': 4,
            'todayAds': 183,
            'todayTrackers': 47,
            'score': 92,
          };
        case 'listMonitors':
        case 'listAccounts':
          return <Map<String, Object>>[];
        case 'listRestrictedTools':
          return [
            {
              'id': 'twikit',
              'title': 'X / Twitter automation',
              'summary': 'Login and account actions stay compiled off.',
              'grade': 'off',
              'active': false,
            },
            {
              'id': 'playwright',
              'title': 'Browser automation',
              'summary': 'The execute path is present but cannot be enabled.',
              'grade': 'safe',
              'active': false,
            },
          ];
        case 'diagnostics':
          return [
            {
              'id': 'core.storage',
              'name': 'Private task storage',
              'pass': true,
              'detail': 'available on device',
            },
            {
              'id': 'core.network',
              'name': 'Network policy gate',
              'pass': true,
              'detail': 'deny-first checks loaded',
            },
          ];
        case 'securityDiagnostics':
          return [
            {
              'id': 'privacy.history',
              'name': 'Browsing history defaults off',
              'pass': true,
              'detail': 'no automatic history writes',
            },
            {
              'id': 'privacy.secrets',
              'name': 'Secrets excluded from debug output',
              'pass': true,
              'detail': 'redaction active',
            },
          ];
        case 'completionStats':
          return {
            'finished': 0,
            'unattended': 0,
            'interrupted': 0,
            'target': 0.9,
            'rate': null,
          };
        case 'runRestrictedTool':
          return {
            'id': 'twikit',
            'ran': true,
            'ok': false,
            'active': false,
            'grade': 'off',
            'reason': 'off (active=false)',
          };
        case 'recentTasks':
          return [
            {
              'id': 1,
              'instruction': 'Find laptops under 500000',
              'status': 'RUNNING',
              'step': 'Extracting prices',
              'progress': 58,
            },
            {
              'id': 2,
              'instruction': 'Download the annual report PDF',
              'status': 'RUNNING',
              'step': 'Downloading',
              'progress': 72,
            },
            {
              'id': 3,
              'instruction': 'Compare phone prices',
              'status': 'COMPLETED',
              'step': '',
              'progress': 100,
            },
          ];
        case 'task':
          return {
            'id': 1,
            'instruction': 'Find laptops under 500000',
            'status': 'COMPLETED',
            'step': '',
            'progress': 100,
            'result': 'I found current laptop listings under 500000 at '
                'https://example.com/laptops',
            'error': '',
            'worker': 'local',
          };
        case 'taskEvents':
          return [
            {
              'seq': 1,
              'type': 'tool.call',
              'detail': 'search search laptops under 500000',
              'at': 1755515100000,
            },
            {
              'seq': 2,
              'type': 'tool.result',
              'detail': 'search ok in 840ms',
              'at': 1755515100840,
            },
            {
              'seq': 3,
              'type': 'tool.call',
              'detail': 'http fetch https://example.com/laptops',
              'at': 1755515101100,
            },
            {
              'seq': 4,
              'type': 'tool.result',
              'detail': 'http ok in 610ms',
              'at': 1755515101710,
            },
          ];
        case 'providerFallback':
          return {'providers': <String>[], 'consent': false};
        case 'canvaMcpStatus':
          return {
            'configured': false,
            'connected': false,
            'endpoint': 'https://mcp.canva.com/mcp',
            'redirectUri': 'mrnobody://oauth/canva',
            'clientId': '',
            'error': '',
          };
        case 'providerConfig':
          return {
            'id': call.arguments['id'],
            'base': 'https://api.groq.com/openai/v1',
            'model': 'llama-3.3-70b-versatile',
            'hasKey': false,
          };
        case 'downloads':
          // The shape the app's own engine returns: named statuses, a folder,
          // and whether the user may press Resume.
          return [
            {
              'id': 1,
              'name': 'report.pdf',
              'size': 2202009,
              'downloaded': 1365245,
              'status': 'RUNNING',
              'percent': 62,
              'folder': 'Documents',
              'resumable': true,
              'canResume': false,
            },
            {
              'id': 2,
              'name': 'Batman.mkv',
              'size': 1073741824,
              'downloaded': 322122547,
              'status': 'PAUSED',
              'percent': 30,
              'folder': 'Movies',
              'resumable': true,
              'canResume': true,
            },
            {
              'id': 3,
              'name': 'image.jpg',
              'size': 491520,
              'downloaded': 491520,
              'status': 'COMPLETED',
              'percent': 100,
              'folder': 'Downloads (system)',
              'resumable': true,
              'canResume': false,
            },
            {
              'id': 4,
              'name': 'archive.zip',
              'size': 0,
              'downloaded': 0,
              'status': 'FAILED',
              'percent': -1,
              'error': 'No connection',
              'folder': 'Downloads (system)',
              'resumable': false,
              'canResume': true,
            },
          ];
        case 'bookmarks':
          return [
            {
              'id': 1,
              'title': 'Example Domain',
              'url': 'https://example.com',
            },
            {
              'id': 2,
              'title': 'Privacy Guides',
              'url': 'https://www.privacyguides.org',
            },
          ];
        case 'memoryInfo':
          return {
            'count': 2,
            'tasks': [
              {
                'id': 1,
                'instruction': 'Find laptops under 500000',
                'status': 'COMPLETED',
                'result': 'Found three current options.',
              },
              {
                'id': 2,
                'instruction': 'Compare phone prices',
                'status': 'FAILED',
                'result': '',
              },
            ],
          };
        default:
          return null;
      }
    },
  );
}
