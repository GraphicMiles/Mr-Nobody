import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/browser/browser_tab.dart';
import 'package:mrnobody/browser/tab_manager.dart';
import 'package:mrnobody/screens/ai_provider_screen.dart';
import 'package:mrnobody/screens/browser_screen.dart';
import 'package:mrnobody/screens/clear_data_screen.dart';
import 'package:mrnobody/screens/downloads_screen.dart';
import 'package:mrnobody/screens/home_screen.dart';
import 'package:mrnobody/screens/launch_screen.dart';
import 'package:mrnobody/screens/privacy_screen.dart';
import 'package:mrnobody/screens/settings_screen.dart';
import 'package:mrnobody/screens/tabs_screen.dart';
import 'package:mrnobody/screens/task_chat_screen.dart';
import 'package:mrnobody/screens/tasks_screen.dart';
import 'package:mrnobody/state/app_state.dart';
import 'package:mrnobody/state/error_log.dart';
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
    BrowserTab.engineFactory = ({required int tabId, required String url, required bool isPrivate}) =>
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

  // The ⓘ overlay's badge renders ErrorLog.instance.count, a process-wide
  // singleton that an earlier screen (or an earlier test in this same isolate)
  // can leave non-zero. A single leaked error flips the badge on every screen
  // that follows — the exact sub-pixel drift this suite kept failing with — so
  // reset it before each capture. This is what made the goldens deterministic
  // rather than merely passing this run.
  setUp(() => ErrorLog.instance.clear());

  Future<void> pumpScreen(WidgetTester tester, Widget child, String golden) async {
    tester.view.physicalSize = phone * 3;
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(
      MaterialApp(
        debugShowCheckedModeBanner: false,
        theme: AppTheme.dark(),
        home: child,
      ),
    );
    await tester.pump(const Duration(milliseconds: 400));
    await expectLater(find.byType(MaterialApp), matchesGoldenFile('goldens/$golden.png'));
  }

  testWidgets('S1 launch', (tester) async {
    await pumpScreen(
      tester,
      LaunchScreen(onStart: () {}, onPrivacy: () {}),
      's1_launch',
    );
  });

  testWidgets('S2 agent home', (tester) async {
    await pumpScreen(
      tester,
      Scaffold(
        backgroundColor: AppColors.bg,
        body: HomeScreen(onSubmit: (_) {}, onShortcut: (_) {}, onOpenTask: (_) {}),
        bottomNavigationBar: BottomNav(selected: 0, onSelect: (_) {}, onNew: () {}),
      ),
      's2_home',
    );
  });

  testWidgets('S2 browser', (tester) async {
    final tabs = TabManager();
    tabs.newTab(url: 'https://example.com');
    await pumpScreen(
      tester,
      BrowserScreen(tabs: tabs, onShowTabs: () {}, onOpenDestination: (_) {}),
      's2_browser',
    );
  });

  testWidgets('S3 tabs', (tester) async {
    final tabs = TabManager();
    tabs.newTab(url: 'https://example.com');
    tabs.newTab(url: 'https://duckduckgo.com/?q=arsenal');
    tabs.newTab(isPrivate: true, url: 'https://news.ycombinator.com');
    await pumpScreen(
      tester,
      Scaffold(
        backgroundColor: AppColors.bg,
        body: TabsScreen(tabs: tabs, onOpenTab: () {}),
        bottomNavigationBar: BottomNav(selected: 1, onSelect: (_) {}, onNew: () {}),
      ),
      's3_tabs',
    );
  });

  testWidgets('S4 privacy', (tester) async {
    await pumpScreen(tester, const PrivacyScreen(), 's4_privacy');
  });

  testWidgets('S5 tasks', (tester) async {
    await pumpScreen(
      tester,
      Scaffold(
        backgroundColor: AppColors.bg,
        body: TasksScreen(onOpenTask: (_) {}),
        bottomNavigationBar: BottomNav(selected: 2, onSelect: (_) {}, onNew: () {}),
      ),
      's5_tasks',
    );
  });

  testWidgets('S5 task detail', (tester) async {
    await pumpScreen(
      tester,
      const TaskChatScreen(
        taskId: 1,
        title: 'Find laptops under 500000',
        instruction: 'Find laptops under 500000',
      ),
      's5_task_detail',
    );
  });

  testWidgets('S6 settings', (tester) async {
    await AppState.instance.load();
    await pumpScreen(
      tester,
      Scaffold(
        backgroundColor: AppColors.bg,
        body: const SettingsScreen(),
        bottomNavigationBar: BottomNav(selected: 3, onSelect: (_) {}, onNew: () {}),
      ),
      's6_settings',
    );
  });

  testWidgets('S6 AI provider', (tester) async {
    await pumpScreen(tester, const AiProviderScreen(initialProvider: 'groq'), 's6_ai_provider');
  });

  testWidgets('S7 clear data', (tester) async {
    await pumpScreen(tester, const ClearDataScreen(), 's7_clear_data');
  });

  testWidgets('S8 downloads', (tester) async {
    await pumpScreen(tester, const DownloadsScreen(), 's8_downloads');
  });
}

/// A stand-in for the Java core, so screens render with representative data.
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
            'fingerprint': false,
            'profile': 'BALANCED',
            'searchEngine': 'https://duckduckgo.com/?q=',
            'provider': 'local',
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
          return <Map<String, Object>>[];
        case 'debugLog':
          // The overlay asks the core for its log so the badge can count
          // core-side failures. In a test there are none by construction.
          return {'entries': <String>[], 'count': 0};
        default:
          return null;
      }
    },
  );
}
