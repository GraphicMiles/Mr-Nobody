import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/screens/memory_screen.dart';

import 'test_fonts.dart';

/// The memory panel. The bug this locks in: a raw Map<Object?, Object?> off the
/// MethodChannel threw when lazily cast to Map<String, dynamic>; each task must
/// be copied with Map.from instead.
void main() {
  setUpAll(loadTestFonts);

  testWidgets('renders tasks decoded from the channel without a cast error',
      (tester) async {
    // The exact shape the Java core returns: the MethodChannel codec produces
    // Map<Object?, Object?> and List<Object?>, not Map<String, dynamic>.
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('mrnobody/core'), (call) async {
      if (call.method == 'memoryInfo') {
        return <Object?, Object?>{
          'count': 1,
          'tasks': <Object?>[
            <Object?, Object?>{
              'id': 1,
              'instruction': 'find laptops',
              'status': 'COMPLETED',
              'result': 'found three',
            },
          ],
        };
      }
      return null;
    });

    await tester.pumpWidget(const MaterialApp(home: MemoryScreen()));
    await tester.pump();
    await tester.pump();

    expect(find.text('find laptops'), findsOneWidget);
    expect(find.textContaining('1 task'), findsOneWidget);
    expect(tester.takeException(), isNull,
        reason: 'a raw Map<Object?, Object?> must not throw a cast error');
  });
}
