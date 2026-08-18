import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/screens/dev_panel_screen.dart';
import 'package:mrnobody/state/error_log.dart';

/// The Phase 1 benchmark panel: shows every check's pass/fail and records
/// failures to the error log, so a device run is a list the user reads off.
void main() {
  Future<void> pumpPanel(WidgetTester tester) async {
    // A tall phone viewport so the whole panel (results + the manual section)
    // renders without the lazy sliver culling anything off-screen.
    tester.view.physicalSize = const Size(390 * 3, 1200 * 3);
    tester.view.devicePixelRatio = 3.0;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(const MaterialApp(home: DevPanelScreen()));
    await tester.pump();
    await tester.pump();
  }

  testWidgets('renders every result and records failures to the log',
      (tester) async {
    ErrorLog.instance.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('mrnobody/core'), (call) async {
      if (call.method == 'diagnostics') {
        return [
          {'id': 'a', 'name': 'A passes', 'pass': true, 'detail': 'ok'},
          {'id': 'b', 'name': 'B fails', 'pass': false, 'detail': 'boom'},
        ];
      }
      return null;
    });

    await pumpPanel(tester);

    // The Java battery plus the two Dart checks (input.route, bridge).
    expect(find.text('A passes'), findsOneWidget);
    expect(find.text('B fails'), findsOneWidget);
    expect(find.byIcon(Icons.check_circle), findsWidgets);
    expect(find.byIcon(Icons.cancel), findsWidgets);

    // The failed check reached the error log, so the ⓘ badge carries it.
    expect(
      ErrorLog.instance.entries.any((e) => e.contains('B fails')),
      isTrue,
      reason: 'a failed benchmark must be recorded, not just shown',
    );

    // The manual section is present for what code cannot observe.
    expect(find.text('Needs your eyes'), findsOneWidget);
    expect(find.text('PASS'), findsWidgets);
  });

  testWidgets('a manual PASS is recorded too', (tester) async {
    ErrorLog.instance.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('mrnobody/core'), (call) async {
      if (call.method == 'diagnostics') return <Map<String, Object>>[];
      return null;
    });

    await pumpPanel(tester);

    await tester.tap(find.text('PASS').first);
    await tester.pump();

    expect(
      ErrorLog.instance.entries.any((e) => e.contains('(manual)')),
      isTrue,
      reason: 'manual observations are recorded the same way',
    );
  });
}
