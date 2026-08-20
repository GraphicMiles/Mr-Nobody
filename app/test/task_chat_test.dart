import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/screens/task_chat_screen.dart';
import 'package:mrnobody/widgets/agent_response.dart';
import 'package:mrnobody/widgets/toast.dart';

import 'test_fonts.dart';

/// The task chat, against a faked core.
///
/// Replaces `task_detail_test.dart`. That screen drew a five-step plan that was
/// hardcoded in Dart and identical for every task — a download reported
/// "Extract prices" — so its tests could only ever assert on fiction. These
/// assert on what the event log actually contains.
///
/// The cancellation guarantee is carried over intact: a task the user regrets
/// must be stoppable, and before it existed `CANCELLED` was a status the core
/// could hold and the app could never produce.
void main() {
  setUpAll(loadTestFonts);

  final calls = <MethodCall>[];
  late String status;
  late String result;
  late List<Map<String, Object>> events;

  setUp(() {
    calls.clear();
    status = 'RUNNING';
    result = '';
    events = [
      {
        'seq': 1,
        'type': 'tool.call',
        'detail': 'search search bitcoin price',
        'at': 1000,
      },
      {
        'seq': 2,
        'type': 'tool.result',
        'detail': 'search ok in 840ms',
        'at': 1840,
      },
      {
        'seq': 3,
        'type': 'tool.call',
        'detail': 'http fetch https://coinmarketcap.com/currencies/bitcoin/',
        'at': 1900,
      },
    ];

    // An empty answer stream by default: most tests drive the answer through
    // the poll + timed reveal. Without a handler the subscription throws
    // MissingPluginException through FlutterError, which fails the test.
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
      const EventChannel('mrnobody/task-stream'),
      MockStreamHandler.inline(
        onListen: (Object? arguments, MockStreamHandlerEventSink events) {},
      ),
    );

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('mrnobody/core'),
      (call) async {
        calls.add(call);
        switch (call.method) {
          case 'task':
            return {
              'id': 7,
              'instruction': 'track the bitcoin price',
              'status': status,
              'step': 'Read sources',
              'progress': 58,
              'result': result,
              'error': '',
              'worker': 'local',
              'createdAt': 1000,
              'updatedAt': 4100,
            };
          case 'taskEvents':
            return events;
          case 'cancelTask':
            status = 'CANCELLED';
            return true;
          case 'runTask':
            return {'id': 8};
          default:
            return null;
        }
      },
    );
  });

  tearDown(() {
    AppToast.dismiss();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('mrnobody/core'), null);
  });

  Future<void> pump(WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(
      home: TaskChatScreen(
        taskId: 7,
        title: 'track the bitcoin price',
        instruction: 'track the bitcoin price',
      ),
    ));
    // A fixed pump: a running task keeps a spinner and a shimmer alive, so
    // pumpAndSettle would never return.
    await tester.pump(const Duration(milliseconds: 300));
  }

  testWidgets('the instruction is shown as the first message', (tester) async {
    await pump(tester);
    expect(find.text('track the bitcoin price'), findsWidgets);
    expect(find.byType(UserTurn), findsOneWidget);
  });

  testWidgets('the trace is built from the event log, not a fixed plan',
      (tester) async {
    await pump(tester);

    // Semantic activities come from the calls that actually happened. The
    // tool syntax itself is subordinate, never the visible hierarchy.
    expect(find.text('Searching broadly'), findsOneWidget);
    expect(find.text('Reading source pages'), findsOneWidget);
    expect(find.text('coinmarketcap.com'), findsOneWidget);

    // The old screen's invented steps must not appear anywhere.
    expect(find.text('Extract prices'), findsNothing);
    expect(find.text('Open candidates'), findsNothing);
    expect(find.text('Compare'), findsNothing);
  });

  testWidgets('a live task offers stop', (tester) async {
    await pump(tester);
    expect(find.byIcon(Icons.stop), findsOneWidget);
    expect(find.byIcon(Icons.arrow_upward), findsNothing);
  });

  testWidgets('tapping stop asks the core to cancel that task id',
      (tester) async {
    await pump(tester);
    await tester.tap(find.byIcon(Icons.stop));
    await tester.pump(const Duration(milliseconds: 300));

    final cancel = calls.where((c) => c.method == 'cancelTask');
    expect(cancel, isNotEmpty, reason: 'stop must reach the core');
    expect((cancel.first.arguments as Map)['id'], 7);

    // Stop raises a confirmation pill, which owns a dismissal timer, and the
    // screen owns a poll timer. Unmount so neither is pending at teardown.
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 4));
  });

  testWidgets('once stopped, the composer offers send again', (tester) async {
    await pump(tester);
    await tester.tap(find.byIcon(Icons.stop));
    await tester.pump(const Duration(milliseconds: 700));

    expect(find.byIcon(Icons.arrow_upward), findsOneWidget);
    expect(find.byIcon(Icons.stop), findsNothing);

    // Unmount so the screen's poll timer is cancelled before the test ends;
    // a pending periodic timer fails the binding's teardown check. The extra
    // pump lets the toast's own dismissal timer expire.
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 4));
  });

  testWidgets('a finished answer is revealed and then settles',
      (tester) async {
    result = 'Bitcoin is 64282.19 dollars today.';
    status = 'COMPLETED';
    await pump(tester);

    // Long enough for the 55ms-per-word reveal to finish.
    await tester.pump(const Duration(seconds: 2));
    expect(find.textContaining('Bitcoin'), findsWidgets);
  });

  testWidgets('a URL in the answer becomes an inline citation',
      (tester) async {
    result = 'The price is on https://coinmarketcap.com/ right now.';
    status = 'COMPLETED';
    await pump(tester);
    await tester.pump(const Duration(seconds: 2));

    expect(find.text('coinmarketcap.com'), findsWidgets);
  });

  testWidgets('sources come only from pages the agent successfully read',
      (tester) async {
    result = 'Done.';
    status = 'COMPLETED';
    events.add({
      'seq': 4,
      'type': 'tool.result',
      'detail': 'http ok in 120ms',
      'at': 2020,
    });
    await pump(tester);
    await tester.pump(const Duration(seconds: 2));

    // One http fetch in the log, so one source — not one per search result.
    expect(find.text('1 source'), findsOneWidget);
  });

  testWidgets('a streamed answer arrives as the tokens land', (tester) async {
    // A live stream for task 7, emitting the answer in pieces and then
    // closing, as a remote provider would.
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
      const EventChannel('mrnobody/task-stream'),
      MockStreamHandler.inline(
        onListen: (Object? arguments, MockStreamHandlerEventSink events) {
          events.success({'taskId': 7, 'type': 'token', 'text': 'Bitcoin '});
          events.success({'taskId': 7, 'type': 'token', 'text': 'is 64282'});
          events.success({'taskId': 7, 'type': 'done', 'text': 'Bitcoin is 64282'});
        },
      ),
    );

    status = 'RUNNING';
    result = '';
    await pump(tester);
    await tester.pump();

    expect(find.textContaining('Bitcoin'), findsWidgets);
    expect(find.textContaining('64282'), findsWidgets);

    // Unmount so the screen's poll timer is cancelled before the test ends.
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('a parked upload offers a visible-tab prompt', (tester) async {
    status = 'WAITING';
    result = '';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('mrnobody/core'),
      (call) async {
        calls.add(call);
        if (call.method == 'task') {
          return {
            'id': 7,
            'instruction': 'upload the form',
            'status': 'WAITING',
            'step': '',
            'progress': 40,
            'result': '',
            'error':
                'File upload needs a visible tab.\nhttps://example.com/form',
            'pendingTool': 'upload',
            'worker': 'local',
            'createdAt': 1000,
            'updatedAt': 4100,
          };
        }
        if (call.method == 'taskEvents') return events;
        return null;
      },
    );

    await pump(tester);
    expect(find.text('Needs a visible tab'), findsOneWidget);
    expect(find.text('Open page'), findsOneWidget);
    expect(find.text("I've finished"), findsOneWidget);
    expect(find.text('Allow'), findsNothing);

    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('polling stops once the task can no longer change',
      (tester) async {
    result = 'Done.';
    status = 'COMPLETED';
    await pump(tester);
    await tester.pump(const Duration(seconds: 2));

    final before = calls.where((c) => c.method == 'task').length;
    await tester.pump(const Duration(seconds: 3));
    final after = calls.where((c) => c.method == 'task').length;

    expect(after, before,
        reason: 'a finished task must not keep waking the core');

    await tester.pumpWidget(const SizedBox());
  });
}
