import 'package:flutter/material.dart';
import 'theme/app_theme.dart';
import 'widgets/bottom_nav.dart';
import 'screens/launch_screen.dart';
import 'screens/home_screen.dart';
import 'screens/tabs_screen.dart';
import 'screens/tasks_screen.dart';
import 'screens/settings_screen.dart';

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

/// The shell owns the bottom nav and the four destination screens.
/// Drill-in screens (task detail, privacy, downloads, clear) are pushed routes.
class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  int _selected = 0;
  bool _launched = true; // first-launch is shown once; keep simple for now

  static const _destinations = [
    HomeScreen(),
    TabsScreen(),
    TasksScreen(),
    SettingsScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    if (!_launched) {
      return LaunchScreen(onStart: () => setState(() => _launched = true));
    }
    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: IndexedStack(index: _selected, children: _destinations),
      ),
      bottomNavigationBar: BottomNav(
        selected: _selected,
        onSelect: (i) {
          if (i == 4) {
            // raised "+" → Agent Home (fresh "new tab")
            setState(() => _selected = 0);
          } else {
            setState(() => _selected = i);
          }
        },
      ),
    );
  }
}
