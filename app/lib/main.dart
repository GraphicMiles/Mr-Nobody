import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'bridge/native_bridge.dart';
import 'browser/tab_manager.dart';
import 'router/intent_router.dart';
import 'screens/browser_screen.dart';
import 'screens/clear_data_screen.dart';
import 'screens/downloads_screen.dart';
import 'screens/home_screen.dart';
import 'screens/launch_screen.dart';
import 'screens/privacy_screen.dart';
import 'screens/settings_screen.dart';
import 'screens/tabs_screen.dart';
import 'screens/task_chat_screen.dart';
import 'screens/tasks_screen.dart';
import 'state/app_state.dart';
import 'state/error_log.dart';
import 'theme/app_theme.dart';
import 'widgets/bottom_nav.dart';
import 'widgets/debug_fab.dart';
import 'widgets/toast.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Framework errors land in the in-app debug overlay: this app has no crash
  // reporter, so the user's own copy button is the only report channel.
  final previous = FlutterError.onError;
  FlutterError.onError = (details) {
    ErrorLog.instance.add(details.exceptionAsString());
    previous?.call(details);
  };
  SystemChrome.setSystemUIOverlayStyle(SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
    systemNavigationBarColor: AppColors.bg,
    systemNavigationBarIconBrightness: Brightness.light,
  ));
  runApp(const MrNobodyApp());
}

class MrNobodyApp extends StatelessWidget {
  const MrNobodyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: AppState.instance,
      builder: (context, _) {
        final themeId = AppState.instance.themeId;
        return MaterialApp(
          title: 'Mr Nobody',
          debugShowCheckedModeBanner: false,
          theme: AppTheme.forTheme(themeId),
          themeAnimationDuration: const Duration(milliseconds: 240),
          themeAnimationCurve: Curves.easeOutCubic,
          builder: (context, child) => AnnotatedRegion<SystemUiOverlayStyle>(
            value: SystemUiOverlayStyle(
              statusBarColor: Colors.transparent,
              statusBarIconBrightness: Brightness.light,
              systemNavigationBarColor: AppColors.bg,
              systemNavigationBarIconBrightness: Brightness.light,
            ),
            child: child ?? const SizedBox.shrink(),
          ),
          // A fresh widget instance (with the current theme id) ensures the
          // stateful shell updates immediately while preserving its State.
          home: AppShell(themeId: themeId),
        );
      },
    );
  }
}

/// Destinations of the bottom nav, in bar order.
enum ShellTab { home, tabs, tasks, settings }

/// The shell owns the bottom nav, the shared [TabManager] and the routing of
/// the unified input + deep links. Drill-in screens are pushed routes.
class AppShell extends StatefulWidget {
  final String themeId;

  const AppShell({super.key, required this.themeId});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> with WidgetsBindingObserver {
  final TabManager _tabs = TabManager();
  final GlobalKey<HomeScreenState> _homeKey = GlobalKey<HomeScreenState>();
  final Map<ShellTab, ScrollController> _scrollControllers = {
    for (final t in ShellTab.values) t: ScrollController(),
  };

  ShellTab _tab = ShellTab.home;
  bool? _launched; // null = still asking the core
  bool _navVisible = true;
  bool _deepLinkPromptOpen = false;
  final Map<ShellTab, double> _lastScrollOffset = {
    for (final t in ShellTab.values) t: 0,
  };

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _listenForDeepLinks();
    _checkFirstLaunch();
    // Listen before the asynchronous initial load so even an immediately
    // resolving platform channel cannot publish a palette before the shell is
    // subscribed.
    AppState.instance.addListener(_onAppStateChanged);
    AppState.instance.load();
    for (final entry in _scrollControllers.entries) {
      entry.value.addListener(() => _onScroll(entry.key, entry.value));
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _revalidateRoute();
  }

  Future<void> _revalidateRoute() async {
    final problem = await AppState.instance.revalidateRoute();
    if (!mounted || problem == null) return;
    AppToast.show(context, problem);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    AppState.instance.removeListener(_onAppStateChanged);
    for (final c in _scrollControllers.values) {
      c.dispose();
    }
    _tabs.dispose();
    super.dispose();
  }

  void _onAppStateChanged() {
    _tabs.applySettingsToAll();
    // Most app widgets use semantic AppColors rather than Theme.of(context).
    // Explicitly rebuilding the persistent shell prevents stale bottom-nav,
    // IndexedStack and overlay colours until the next tab selection.
    if (mounted) setState(() {});
  }

  Future<void> _checkFirstLaunch() async {
    final done = await NativeBridge.guard(
      NativeBridge.isFirstLaunchDone,
      true, // core unavailable → don't block the user behind the welcome screen
      'first-launch flag unavailable',
    );
    if (!mounted) return;
    setState(() => _launched = done);
  }

  /// Hide the bar while the user scrolls down, bring it back on the way up —
  /// `.bottombar.nav-hidden` in the wireframe.
  void _onScroll(ShellTab tab, ScrollController controller) {
    if (tab != _tab || !controller.hasClients) return;
    final offset = controller.offset;
    final delta = offset - (_lastScrollOffset[tab] ?? 0);
    if (delta.abs() < 12) return;
    _lastScrollOffset[tab] = offset;
    final shouldShow = delta < 0 || offset <= 0;
    if (shouldShow != _navVisible) setState(() => _navVisible = shouldShow);
  }

  // ----------------------------------------------------------- deep linking

  void _listenForDeepLinks() {
    const ch = MethodChannel('mrnobody/deeplink');
    ch.setMethodCallHandler((call) async {
      if (call.method == 'link' && call.arguments is String) {
        _handleDeepLink(call.arguments as String);
      }
    });
    // Pull only after the Dart handler exists and the first frame can safely
    // show a route/dialog. Native queues the cold VIEW intent until this call.
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      try {
        final initial = await ch.invokeMethod<String>('getInitialLink');
        if (mounted && initial != null && initial.isNotEmpty) {
          _handleDeepLink(initial);
        }
      } on PlatformException catch (e) {
        ErrorLog.instance
            .add('initial deep link unavailable: ${e.message ?? e.code}');
      }
    });
  }

  void _handleDeepLink(String uri) {
    if (uri.startsWith('http://') || uri.startsWith('https://')) {
      _openBrowser(IntentRouter.toUrl(uri));
      return;
    }
    if (!uri.startsWith('mrnobody://')) return;

    final body = uri.substring('mrnobody://'.length);
    final parts = body.split('?');
    final path = parts.first;
    Map<String, String> q;
    try {
      q = parts.length > 1
          ? Uri.splitQueryString(parts.sublist(1).join('?'))
          : const <String, String>{};
    } on FormatException {
      ErrorLog.instance.add('malformed deep link ignored');
      return;
    }

    switch (path) {
      case 'open':
        final url = q['url'] ?? '';
        if (url.isNotEmpty) _openBrowser(IntentRouter.toUrl(url));
        break;
      case 'search':
        final query = q['q'] ?? '';
        // Route through the same classifier as the address bar, so a deep
        // link that is actually a question ("what is X's age") reaches the
        // agent instead of opening a raw results page.
        if (query.isNotEmpty) _route(query);
        break;
      case 'task':
        final idStr = q['id'] ?? '';
        final id = int.tryParse(idStr);
        if (id != null) {
          _openWaitingTask(id);
          break;
        }
        final instruction = q['instruction'] ?? '';
        if (instruction.isNotEmpty) _confirmDeepLinkedTask(instruction);
        break;
      case 'tabs':
        _select(ShellTab.tabs);
        break;
      case 'tasks':
        _select(ShellTab.tasks);
        break;
      case 'settings':
        _select(ShellTab.settings);
        break;
      case 'privacy':
        _push(const PrivacyScreen());
        break;
      case 'downloads':
        _push(const DownloadsScreen());
        break;
      case 'clear':
        _push(ClearDataScreen(
            onBeforeBrowserDataClear: _onBeforeBrowserDataClear));
        break;
      default:
        ErrorLog.instance.add('unknown deep link: $uri');
    }
  }

  /// A custom-scheme intent is controlled by another app or a web page. It may
  /// suggest work, but it must never silently create network traffic, provider
  /// charges, or files. The user sees the decoded instruction before enqueue.
  Future<void> _confirmDeepLinkedTask(String instruction) async {
    if (!mounted || _deepLinkPromptOpen) return;
    if (instruction.length > 4000) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text('This linked task is too long to review safely.')),
      );
      return;
    }
    _deepLinkPromptOpen = true;
    bool? allowed;
    try {
      allowed = await showDialog<bool>(
        context: context,
        barrierDismissible: true,
        builder: (dialogContext) => AlertDialog(
          title: AppColors.isWarm
              ? Text(
                  'Start this agent task?',
                  style: AppTheme.sans(
                    size: 17,
                    color: AppColors.overlayInk,
                    w: FontWeight.w700,
                  ),
                )
              : const Text('Start this agent task?'),
          content: SingleChildScrollView(
            child: AppColors.isWarm
                ? SelectableText(
                    instruction,
                    style: AppTheme.sans(
                      size: 12.5,
                      color: AppColors.overlayMuted,
                      height: 1.5,
                    ),
                  )
                : SelectableText(instruction),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: AppColors.isWarm
                  ? Text(
                      'Cancel',
                      style: AppTheme.sans(
                        size: 13,
                        color: AppColors.overlayMuted,
                        w: FontWeight.w600,
                      ),
                    )
                  : const Text('Cancel'),
            ),
            FilledButton(
              style: AppColors.isWarm
                  ? FilledButton.styleFrom(
                      backgroundColor: AppColors.overlayInk,
                      foregroundColor: AppColors.overlay,
                    )
                  : null,
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('Start task'),
            ),
          ],
        ),
      );
    } finally {
      _deepLinkPromptOpen = false;
    }
    if (allowed == true && mounted) await _runTask(instruction);
  }

  // -------------------------------------------------------------- routing

  /// Route a unified-input line: URL/search → visible browser, instruction →
  /// the agent core (V1 §5). Slash commands (/agent, /task, /download,
  /// /search, /open) are stripped to their payload before dispatch.
  void _route(String input) {
    final type = IntentRouter.route(input);
    final payload = IntentRouter.payload(input);
    if (type == IntentType.task) {
      _runTask(payload.trim());
      return;
    }
    _openBrowser(IntentRouter.toUrl(payload));
  }

  Future<void> _runTask(String instruction) async {
    final result = await NativeBridge.guard(
      () => NativeBridge.runTask(instruction),
      const <String, dynamic>{},
      'could not start task',
    );
    if (!mounted) return;
    final id = (result['id'] as num?)?.toInt();
    if (id == null) {
      AppToast.show(context, 'Agent core unavailable');
      return;
    }
    _homeKey.currentState?.refresh();
    // Straight into the conversation. The toast that used to fire here said
    // "Task started" and then left the user on Home with nothing to look at;
    // the thread itself is the confirmation, and it shows the work arriving.
    _push(TaskChatScreen(
      taskId: id,
      title: instruction,
      instruction: instruction,
      onOpenUrl: _openUrlFromTask,
    ));
  }

  void _openBrowser(String url) {
    final tab = _tabs.active ?? _tabs.newTab();
    if (url.isNotEmpty) tab.engine.loadUrl(url);
    _pushBrowser();
  }

  /// A waiting task asked the user to finish something on a real page
  /// (file upload, sign-in). Open it in a new tab so the chat stays
  /// underneath and they can come back to tap "I've finished".
  void _openUrlFromTask(String url) {
    _tabs.newTab(url: url);
    _pushBrowser();
  }

  void _pushBrowser() {
    Navigator.of(context).push(
      PageRouteBuilder<void>(
        settings: const RouteSettings(name: 'browser'),
        transitionDuration: Duration.zero,
        reverseTransitionDuration: Duration.zero,
        pageBuilder: (_, __, ___) => BrowserScreen(
          tabs: _tabs,
          onShowTabs: () {
            Navigator.of(context).popUntil((r) => r.isFirst);
            _select(ShellTab.tabs);
          },
          onOpenDestination: (dest) {
            switch (dest) {
              case BrowserDestination.privacy:
                _push(const PrivacyScreen());
                break;
              case BrowserDestination.settings:
                _push(SettingsScreen(
                    onBeforeBrowserDataClear: _onBeforeBrowserDataClear,
                    onOpenUrl: _openUrlFromTask));
                break;
              case BrowserDestination.downloads:
                _push(const DownloadsScreen());
                break;
            }
          },
        ),
      ),
    );
  }

  /// Open the browser without stacking a second copy of it.
  void _showBrowser() {
    final route = ModalRoute.of(context);
    if (route?.settings.name == 'browser') return;
    _pushBrowser();
  }

  void _push(Widget screen) {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => screen));
  }

  Future<void> _onBeforeBrowserDataClear() async {
    await _tabs.closePrivateTabs();
    // Removing a platform-view widget takes effect at the frame boundary.
    // Do not ask ProfileStore to delete its profile while Flutter may still
    // have the private Android view mounted behind the Settings route.
    await WidgetsBinding.instance.endOfFrame;
  }

  void _select(ShellTab tab) {
    setState(() {
      _tab = tab;
      _navVisible = true;
    });
  }

  /// Open a task as a conversation.
  ///
  /// Both entry points land here: tapping a row in Tasks, and submitting a
  /// prompt from the address bar. The old detail screen drew a five-step plan
  /// that was hardcoded and identical for every task, so a download reported
  /// "Extract prices"; the chat shows the event log instead, which can only
  /// contain work that happened.
  Future<void> _openWaitingTask(int id) async {
    final task = await NativeBridge.guard(
      () => NativeBridge.task(id),
      null,
      'task unavailable',
    );
    if (!mounted) return;
    if (task == null) {
      _select(ShellTab.tasks);
      return;
    }
    _openTask(task);
  }

  void _openTask(Map<String, dynamic> task) {
    final instruction = task['instruction'] as String? ?? 'Task';
    _push(TaskChatScreen(
      taskId: (task['id'] as num?)?.toInt(),
      title: instruction,
      instruction: instruction,
    ));
  }

  // ---------------------------------------------------------------- build

  @override
  Widget build(BuildContext context) {
    if (_launched == null) {
      return Scaffold(
        backgroundColor: AppColors.bg,
        body: Center(child: CircularProgressIndicator(color: AppColors.accent)),
      );
    }
    if (_launched == false) {
      return LaunchScreen(
        onStart: () {
          NativeBridge.guard(
              NativeBridge.setFirstLaunchDone, null, 'first-launch flag');
          setState(() => _launched = true);
        },
        onPrivacy: () {
          NativeBridge.guard(
              NativeBridge.setFirstLaunchDone, null, 'first-launch flag');
          setState(() => _launched = true);
          WidgetsBinding.instance
              .addPostFrameCallback((_) => _push(const PrivacyScreen()));
        },
      );
    }

    return Scaffold(
      backgroundColor: AppColors.bg,
      body: Stack(
        children: [
          Positioned.fill(
            child: IndexedStack(
              index: _tab.index,
              children: [
                HomeScreen(
                  key: _homeKey,
                  isActive: _tab == ShellTab.home,
                  scrollController: _scrollControllers[ShellTab.home],
                  onSubmit: _route,
                  onOpenTask: _openTask,
                  onShortcut: (s) {
                    switch (s) {
                      case HomeShortcut.tabs:
                        _select(ShellTab.tabs);
                        break;
                      case HomeShortcut.tasks:
                        _select(ShellTab.tasks);
                        break;
                      case HomeShortcut.downloads:
                        _push(const DownloadsScreen());
                        break;
                      case HomeShortcut.settings:
                        _select(ShellTab.settings);
                        break;
                    }
                  },
                ),
                TabsScreen(tabs: _tabs, onOpenTab: _showBrowser),
                TasksScreen(
                  isActive: _tab == ShellTab.tasks,
                  scrollController: _scrollControllers[ShellTab.tasks],
                  onOpenTask: _openTask,
                ),
                SettingsScreen(
                  scrollController: _scrollControllers[ShellTab.settings],
                  onBack: () => _select(ShellTab.home),
                  onBeforeBrowserDataClear: _onBeforeBrowserDataClear,
                  onOpenUrl: _openUrlFromTask,
                ),
              ],
            ),
          ),
          // The ⓘ overlay rides above every destination, as in the wireframe.
          // The body already ends above the bar, so it only needs a small gap.
          const Positioned.fill(child: DebugOverlay(bottomInset: 18)),
        ],
      ),
      bottomNavigationBar: BottomNav(
        selected: _tab.index,
        visible: _navVisible,
        onSelect: (i) => _select(ShellTab.values[i]),
        onNew: () {
          _tabs.newTab();
          AppToast.show(context, 'New tab');
          _showBrowser();
        },
      ),
    );
  }
}
