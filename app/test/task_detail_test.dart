import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/screens/task_detail_screen.dart';
import 'package:mrnobody/theme/app_theme.dart';
import 'package:mrnobody/widgets/toast.dart';

import 'test_fonts.dart';

/// A task the user regrets must be stoppable. Before this, `CANCELLED` existed
/// in the core's status enum and nothing in the app could ever produce it.
void main() {
  setUpAll(loadTestFonts);

  final calls = <MethodCall>[];
  String status = 'RUNNING';

  setUp(() {
    calls.clear();
    status = 'RUNNING';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('mrnobody/core'),
      (call) async {
        calls.add(call);
        switch (call.method) {
          case 'task':
            return {
              'id': 7,
              'instruction': 'Find laptops under 500000',
              'status': status,
              'step': 'Extracting prices',
              'progress': 58,
              'result': '',
              'error': '',
              'worker': 'local',
            };
          case 'cancelTask':
            status = 'CANCELLED';
            return true;
          default:
            return null;
        }
      },
    );
  });

  tearDown(() {
    AppToast.dismiss(); // the confirmation pill owns a dismissal timer
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('mrnobody/core'), null);
  });

  Future<void> pump(WidgetTester tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.dark(),
      home: const TaskDetailScreen(
        taskId: 7,
        title: 'Find laptops under 500000',
        initialStatus: 'RUNNING',
        initialStep: 'Extracting prices',
        initialProgress: 58,
      ),
    ));
    await tester.pump(const Duration(milliseconds: 300));
  }

  testWidgets('a live task offers Stop, not Run again', (tester) async {
    await pump(tester);
    expect(find.text('Stop task'), findsOneWidget);
    expect(find.text('Run again'), findsNothing);
  });

  testWidgets('tapping Stop asks the core to cancel that task id', (tester) async {
    await pump(tester);
    await tester.tap(find.text('Stop task'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 2)); // let the toast retire

    final cancel = calls.where((c) => c.method == 'cancelTask');
    expect(cancel, hasLength(1), reason: 'the request must reach the core');
    expect((cancel.first.arguments as Map)['id'], 7);
  });

  testWidgets('once stopped, the task offers Run again instead', (tester) async {
    await pump(tester);
    await tester.tap(find.text('Stop task'));
    // The screen re-reads the task after asking; the core now reports CANCELLED.
    await tester.pump();
    await tester.pump(const Duration(seconds: 2));

    expect(find.text('Run again'), findsOneWidget);
    expect(find.text('Stop task'), findsNothing);
  });
}
