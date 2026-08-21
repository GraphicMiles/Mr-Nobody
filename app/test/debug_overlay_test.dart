import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/state/error_log.dart';
import 'package:mrnobody/theme/app_theme.dart';
import 'package:mrnobody/widgets/debug_fab.dart';

import 'test_fonts.dart';

/// The ⓘ overlay reported "0 errors" while the user was looking at an AI
/// provider 404. The reason: two error logs. Everything that fails in the Java
/// core — a provider rejecting a model, a tool breaking its contract, a task
/// failing in a background worker — never passes through a Dart try/catch, and
/// nothing was asking the core for its log.
void main() {
  setUpAll(loadTestFonts);

  List<String> nativeEntries = [];
  var debugLogCalls = 0;

  setUp(() {
    ErrorLog.instance.clear();
    nativeEntries = [];
    debugLogCalls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('mrnobody/core'),
      (call) async {
        if (call.method == 'debugLog') {
          debugLogCalls++;
          return {'entries': nativeEntries, 'count': nativeEntries.length};
        }
        return null;
      },
    );
  });

  tearDown(() {
    ErrorLog.instance.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('mrnobody/core'), null);
  });

  Future<void> pumpOverlay(WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.dark(),
      home: Scaffold(
        backgroundColor: AppColors.bg,
        body: const Stack(children: [Positioned.fill(child: DebugOverlay())]),
      ),
    ));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
  }

  testWidgets('the badge counts what the core saw, not just what Dart caught',
      (tester) async {
    nativeEntries = [
      'AI provider: HTTP 404: model `llama-3.3-70b-versatile` does not exist',
    ];

    await pumpOverlay(tester);

    expect(debugLogCalls, greaterThan(0), reason: 'the overlay must ask the core');
    expect(find.text('1'), findsOneWidget, reason: 'the badge shows the core error');
  });

  testWidgets('opening the panel shows the core message itself', (tester) async {
    nativeEntries = ['AI provider: HTTP 404: model does not exist'];
    await pumpOverlay(tester);

    await tester.tap(find.byIcon(Icons.info_outline));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.textContaining('HTTP 404'), findsOneWidget);
    expect(find.text('1 error'), findsOneWidget);
  });

  testWidgets('dart-side and core-side errors are counted together', (tester) async {
    nativeEntries = ['task 3 failed: search failed'];
    ErrorLog.instance.add('settings load failed: MissingPluginException');

    await pumpOverlay(tester);

    expect(find.text('2'), findsOneWidget);
  });

  test('refreshing the core log replaces its entries instead of stacking them', () {
    final log = ErrorLog.instance;
    log.setNative(['a', 'b']);
    expect(log.count, 2);

    log.setNative(['a', 'b']); // same poll again
    expect(log.count, 2, reason: 'a repeated poll must not duplicate entries');

    log.setNative(['a', 'b', 'c']);
    expect(log.count, 3);

    log.add('dart side');
    expect(log.count, 4);
    expect(log.dump, contains('c'));
    expect(log.dump, contains('dart side'));
  });

  test('clearing removes both sides', () {
    final log = ErrorLog.instance;
    log.setNative(['native']);
    log.add('dart');
    log.clear();
    expect(log.count, 0);
    expect(log.dump, 'no errors');
  });
}
