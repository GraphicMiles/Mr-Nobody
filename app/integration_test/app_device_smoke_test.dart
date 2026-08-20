import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:mrnobody/bridge/native_bridge.dart';
import 'package:mrnobody/main.dart' as app;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('first launch, persisted settings, local task, and Thought UI',
      (tester) async {
    const core = MethodChannel('mrnobody/core');
    expect(await core.invokeMethod<bool>('resetDeviceSmokeState'), isTrue);

    app.main();
    await _pumpUntil(tester, find.text('Start browsing'));
    await tester.tap(find.text('Start browsing'));
    await _pumpUntil(tester, find.text('Active tasks'));
    expect(find.text('No active tasks'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.settings_rounded).last);
    await _pumpUntil(tester, find.text('Search suggestions'));
    await tester.tap(find.text('Search suggestions'));
    await tester.pump(const Duration(milliseconds: 400));
    expect((await NativeBridge.getSettings())['suggestions'], isTrue);

    // Rebuild the complete shell against the native store. This exercises the
    // same first-launch/settings read path used by a recreated Activity.
    await tester.pumpWidget(const app.MrNobodyApp());
    await _pumpUntil(tester, find.text('Active tasks'));
    expect((await NativeBridge.getSettings())['suggestions'], isTrue);
    await tester.tap(find.byIcon(Icons.settings_rounded).last);
    await _pumpUntil(tester, find.text('Search suggestions'));
    await tester.tap(find.text('Search suggestions'));
    await tester.pump(const Duration(milliseconds: 400));
    expect((await NativeBridge.getSettings())['suggestions'], isFalse);

    await tester.tap(find.byIcon(Icons.home_rounded).last);
    await _pumpUntil(tester, find.text('Active tasks'));
    final input = find.byType(TextField).first;
    await tester.enterText(input, 'hi');
    await tester.testTextInput.receiveAction(TextInputAction.go);

    await _pumpUntil(
      tester,
      find.text('Hi. What would you like me to do next?'),
      timeout: const Duration(seconds: 45),
    );
    final thought = find.textContaining('Thought for');
    expect(thought, findsOneWidget);
    await tester.tap(thought);
    await _pumpUntil(tester, find.text('Understanding the request'));
    expect(find.text('Responding'), findsOneWidget);

    await tester.tap(thought);
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.text('Understanding the request'), findsNothing);
    expect(find.text('Responding'), findsNothing);
  });
}

Future<void> _pumpUntil(
  WidgetTester tester,
  Finder finder, {
  Duration timeout = const Duration(seconds: 20),
}) async {
  final deadline = DateTime.now().add(timeout);
  while (finder.evaluate().isEmpty && DateTime.now().isBefore(deadline)) {
    await tester.pump(const Duration(milliseconds: 200));
  }
  expect(finder, findsWidgets);
}
