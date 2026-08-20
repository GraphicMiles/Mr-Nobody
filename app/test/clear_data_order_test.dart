import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/screens/clear_data_screen.dart';
import 'package:mrnobody/theme/app_theme.dart';
import 'package:mrnobody/widgets/toast.dart';

void main() {
  final core = const MethodChannel('mrnobody/core');
  final events = <String>[];

  setUp(() {
    events.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(core, (call) async {
      if (call.method == 'clearData') events.add('native-clear');
      if (call.method == 'debugLog') {
        return <String, dynamic>{'entries': <String>[], 'count': 0};
      }
      return <String, dynamic>{};
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(core, null);
  });

  testWidgets('private owners close before native profile deletion starts', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.dark(),
      home: ClearDataScreen(
        onBeforeBrowserDataClear: () async {
          events.add('owners-released');
        },
      ),
    ));

    await tester.tap(find.text('Clear data').last);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(events, <String>['owners-released', 'native-clear']);
    AppToast.dismiss();
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump();
  });
}
