import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/screens/settings_screen.dart';
import 'package:mrnobody/state/app_state.dart';
import 'package:mrnobody/theme/app_theme.dart';
import 'package:mrnobody/widgets/anchored_menu.dart';
import 'package:mrnobody/widgets/debug_fab.dart';
import 'package:mrnobody/widgets/menu_sheet.dart';

import 'test_fonts.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('mrnobody/core');
  final calls = <MethodCall>[];
  var storedTheme = AppColors.classicId;

  Map<String, Object> settings() => {
        'history': false,
        'js': true,
        'suggestions': false,
        'terminal': false,
        'blocking': true,
        'paramStripping': true,
        'profile': 'BALANCED',
        'provider': 'local',
        'privacyMode': 'NORMAL',
        'searchEngine': 'https://duckduckgo.com/?q=',
        'approvalMode': 'CAUTIOUS',
        'resourcePolicy': 'OFF',
        'theme': storedTheme,
      };

  setUpAll(loadTestFonts);

  setUp(() {
    calls.clear();
    storedTheme = AppColors.classicId;
    AppColors.use(AppColors.classicId);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'getSettings':
          return settings();
        case 'setSetting':
          final arguments = Map<String, Object?>.from(call.arguments as Map);
          if (arguments['key'] == 'theme') {
            storedTheme = arguments['value'] as String;
          }
          return null;
        case 'engineInfo':
          return {
            'engine': 'Android System WebView',
            'multiProfile': true,
            'documentStartScript': true,
            'proxyOverride': true,
          };
        case 'getProxy':
          return {
            'route': 'direct',
            'kind': 'http',
            'host': '',
            'port': 0,
          };
        case 'debugLog':
          return {'entries': <String>[], 'count': 0};
        default:
          return null;
      }
    });
  });

  tearDown(() {
    AppColors.use(AppColors.classicId);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('classic retains the production monochrome palette', () {
    final theme = AppTheme.dark();

    expect(AppColors.isWarm, isFalse);
    expect(AppColors.bg, const Color(0xFF000000));
    expect(AppColors.surface, const Color(0xFF101010));
    expect(AppColors.surface2, const Color(0xFF181818));
    expect(AppColors.surface3, const Color(0xFF212121));
    expect(AppColors.accent, const Color(0xFFFFFFFF));
    expect(theme.scaffoldBackgroundColor, const Color(0xFF000000));
    expect(AppTheme.backdrop.gradient, isNull);
  });

  test('warm applies the approved cream palette to app and overlay surfaces',
      () {
    final theme = AppTheme.forTheme(AppColors.warmId);

    expect(AppColors.isWarm, isTrue);
    expect(AppColors.bg, const Color(0xFF0C0D0E));
    expect(AppColors.surface, const Color(0xFF151515));
    expect(AppColors.accent, const Color(0xFFF1DAC6));
    expect(AppColors.accentInk, const Color(0xFF181512));
    expect(AppColors.overlay, const Color(0xFFF1DAC6));
    expect(AppColors.overlayInk, const Color(0xFF181512));
    expect(theme.dialogTheme.backgroundColor, AppColors.overlay);
    expect(theme.snackBarTheme.backgroundColor, AppColors.overlay);
    expect(AppTheme.backdrop.gradient, isA<RadialGradient>());
  });

  test('unknown and retired theme ids safely migrate to classic', () async {
    for (final id in ['system', 'dark', 'light', 'unknown']) {
      storedTheme = id;
      await AppState.instance.load();
      expect(AppState.instance.themeId, AppColors.classicId, reason: id);
      expect(AppState.instance.themeLabel, 'Classic dark', reason: id);
    }
  });

  test('warm loads from the native owner and keeps its user-facing label',
      () async {
    storedTheme = AppColors.warmId;

    await AppState.instance.load();

    expect(AppState.instance.themeId, AppColors.warmId);
    expect(AppState.instance.themeLabel, 'Warm cream');
  });

  test('theme changes are validated and persisted through the core', () async {
    await AppState.instance.setTheme('WARM');

    expect(AppState.instance.themeId, AppColors.warmId);
    expect(storedTheme, AppColors.warmId);
    expect(
      calls.where((call) => call.method == 'setSetting').last.arguments,
      {'key': 'theme', 'value': AppColors.warmId},
    );

    await AppState.instance.setTheme('not-a-theme');
    expect(AppState.instance.themeId, AppColors.classicId);
    expect(storedTheme, AppColors.classicId);
  });

  testWidgets('warm anchored menus and sheets use cream decision surfaces',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.forTheme(AppColors.warmId),
        home: Scaffold(
          body: Builder(
            builder: (context) => Column(
              children: [
                Builder(
                  builder: (anchorContext) => TextButton(
                    onPressed: () => showAnchoredMenu<String>(
                      context: anchorContext,
                      title: 'Theme',
                      options: const [
                        MenuOption(
                          id: 'warm',
                          label: 'Warm cream',
                          icon: Icons.light_mode_outlined,
                        ),
                      ],
                    ),
                    child: const Text('Open picker'),
                  ),
                ),
                TextButton(
                  onPressed: () => showMenuSheet(
                    context,
                    [SheetItem(Icons.check, 'Sheet action', () {})],
                  ),
                  child: const Text('Open sheet'),
                ),
              ],
            ),
          ),
        ),
      ),
    );

    bool hasCreamDecoration() => tester
        .widgetList<Container>(find.byType(Container))
        .map((container) => container.decoration)
        .whereType<BoxDecoration>()
        .any((decoration) => decoration.color == AppColors.overlay);

    await tester.tap(find.text('Open picker'));
    await tester.pumpAndSettle();
    expect(find.text('THEME'), findsOneWidget);
    expect(hasCreamDecoration(), isTrue);
    await tester.tap(find.text('Warm cream'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Open sheet'));
    await tester.pumpAndSettle();
    expect(find.text('Sheet action'), findsOneWidget);
    expect(hasCreamDecoration(), isTrue);
    await tester.tap(find.text('Sheet action'));
    await tester.pumpAndSettle();
  });

  testWidgets('warm debug overlay opens as a cream decision surface',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.forTheme(AppColors.warmId),
        home: const Scaffold(
          body: Stack(
            children: [
              Positioned.fill(child: DebugOverlay(bottomInset: 0)),
            ],
          ),
        ),
      ),
    );
    await tester.pump();
    await tester.tap(find.byIcon(Icons.info_outline));
    await tester.pump(const Duration(milliseconds: 300));

    final panelFound = tester
        .widgetList<Container>(find.byType(Container))
        .map((container) => container.decoration)
        .whereType<BoxDecoration>()
        .any((decoration) => decoration.color == AppColors.overlay);
    expect(panelFound, isTrue);
    expect(find.text('DEBUG'), findsOneWidget);

    // Dispose the overlay so its native-log polling timer is cancelled.
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('Settings lets the user select Warm cream', (tester) async {
    await AppState.instance.load();
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.dark(),
        home: const SettingsScreen(),
      ),
    );
    await tester.pump(const Duration(milliseconds: 300));

    await tester.tap(find.text('Theme'));
    await tester.pumpAndSettle();
    expect(find.text('Warm cream'), findsOneWidget);

    await tester.tap(find.text('Warm cream'));
    await tester.pumpAndSettle();

    expect(AppState.instance.themeId, AppColors.warmId);
    expect(storedTheme, AppColors.warmId);
    expect(find.text('Warm cream'), findsOneWidget);

    // Let the confirmation toast's self-dismiss timer complete.
    await tester.pump(const Duration(seconds: 2));
  });
}
