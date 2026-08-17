import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'theme/app_theme.dart';
import 'widgets/bottom_nav.dart';
import 'browser/tab_manager.dart';
import 'bridge/native_bridge.dart';
import 'router/intent_router.dart';
import 'screens/home_screen.dart';
import 'screens/tabs_screen.dart';
import 'screens/tasks_screen.dart';
import 'screens/settings_screen.dart';
import 'screens/launch_screen.dart';
import 'screens/browser_screen.dart';
import 'screens/task_detail_screen.dart';
import 'screens/privacy_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MrNobodyApp());
}

class MrNobodyApp extends StatelessWidget {
  const MrNobodyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Mr Nobody',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark(),
      home: const AppShell(),
    );
  }
}

/// The shell owns the bottom nav, the shared [TabManager], and the routing of
/// the unified input + deep links. Drill-in screens are pushed routes.
class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  final TabManager _tabs = TabManager();
  int _selected = 0;
  bool? _launched; // null = loading, false = show launch, true = shell

  @override
  void initState() {
    super.initState();
    _listenForDeepLinks();
    _checkFirstLaunch();
  }

  Future<void> _checkFirstLaunch() async {
    try {
      final done = await NativeBridge.isFirstLaunchDone();
      if (!mounted) return;
      setState(() => _launched = done);
    } catch (_) {
      if (mounted) setState(() => _launched = true); // core unavailable → proceed
    }
  }

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  /// Java forwards mrnobody:// and shared http(s) URLs to Dart here.
  void _listenForDeepLinks() {
    const ch = MethodChannel('mrnobody/deeplink');
    ch.setMethodCallHandler((call) async {
      if (call.method == 'link' && call.arguments is String) {
        _handleDeepLink(call.arguments as String);
      }
    });
  }

  void _handleDeepLink(String uri) {
    if (uri.startsWith('mrnobody://')) {
      final body = uri.substring('mrnobody://'.length);
      final parts = body.split('?');
      final path = parts[0];
      final q = parts.length > 1 ? Uri.splitQueryString(parts[1]) : <String, String>{};
      switch (path) {
        case 'search':
          _openBrowser(IntentRouter.toUrl(q['q'] ?? ''));
          break;
        case 'open':
          _openBrowser(q['url'] ?? '');
          break;
        case 'task':
          if ((q['instruction'] ?? '').isNotEmpty) _runTask(q['instruction']!);
          break;
        case 'tabs':
          setState(() => _selected = 1);
          break;
        case 'tasks':
          setState(() => _selected = 2);
          break;
        case 'settings':
          setState(() => _selected = 3);
          break;
      }
    } else if (uri.startsWith('http://') || uri.startsWith('https://')) {
      _openBrowser(uri);
    }
  }

  /// Route a unified-input line: URL/search → browser, task → agent core.
  void _route(String input) {
    final type = IntentRouter.route(input);
    if (type == IntentType.task) {
      _runTask(input.trim());
      return;
    }
    _openBrowser(IntentRouter.toUrl(input));
  }

  void _runTask(String instruction) {
    NativeBridge.runTask(instruction).then((r) {
      if (!mounted) return;
      Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => TaskDetailScreen(title: instruction)),
      );
    }).catchError((e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Task failed: $e')));
      }
    });
  }

  void _openBrowser(String url) {
    final tab = _tabs.active ?? _tabs.newTab();
    if (url.isNotEmpty) tab.engine.loadUrl(url);
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => BrowserScreen(tabs: _tabs)),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (_launched == null) {
      return const Scaffold(body: Center(child: CircularProgressIndicator(color: AppColors.accent)));
    }
    if (_launched == false) {
      return LaunchScreen(
        onStart: () {
          NativeBridge.setFirstLaunchDone();
          setState(() => _launched = true);
        },
        onPrivacy: () {
          NativeBridge.setFirstLaunchDone();
          setState(() => _launched = true);
          Navigator.of(context).push(
            MaterialPageRoute(builder: (_) => const PrivacyScreen()),
          );
        },
      );
    }
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: IndexedStack(
          index: _selected,
          children: [
            HomeScreen(onSubmit: _route),
            TabsScreen(tabs: _tabs, onOpenTab: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => BrowserScreen(tabs: _tabs)),
            )),
            TasksScreen(onOpenTask: (t) => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => TaskDetailScreen(title: t)),
            )),
            const SettingsScreen(),
          ],
        ),
      ),
      bottomNavigationBar: BottomNav(
        selected: _selected,
        onSelect: (i) {
          if (i == 4) {
            // raised "+" → new tab → browser
            _tabs.newTab();
            _openBrowser('');
          } else {
            setState(() => _selected = i);
          }
        },
      ),
    );
  }
}
