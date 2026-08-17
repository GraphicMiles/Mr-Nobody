import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/theme/app_theme.dart';
import 'package:mrnobody/widgets/bottom_nav.dart';

import 'test_fonts.dart';

/// The raised "+" used to be drawn with a negative offset, which put its top
/// half outside the bar's box: clipped when painting and invisible to
/// hit-testing. These tests pin both halves down.
void main() {
  setUpAll(loadTestFonts);

  Future<void> pumpNav(WidgetTester tester, {required VoidCallback onNew, ValueChanged<int>? onSelect}) {
    return tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.dark(),
        home: Scaffold(
          backgroundColor: AppColors.bg,
          body: const SizedBox.expand(),
          bottomNavigationBar: BottomNav(
            selected: 0,
            onSelect: onSelect ?? (_) {},
            onNew: onNew,
          ),
        ),
      ),
    );
  }

  testWidgets('the raised + is fully inside the bar it belongs to', (tester) async {
    await pumpNav(tester, onNew: () {});

    final navBox = tester.getRect(find.byType(BottomNav));
    final plus = find.widgetWithIcon(Container, Icons.add);
    final plusBox = tester.getRect(plus);

    expect(plusBox.top, greaterThanOrEqualTo(navBox.top),
        reason: 'the + must not be painted above its parent, or it gets clipped');
    expect(plusBox.bottom, lessThanOrEqualTo(navBox.bottom));
    expect(plusBox.width, greaterThan(0));
  });

  testWidgets('tapping the TOP edge of the + still creates a tab', (tester) async {
    var taps = 0;
    await pumpNav(tester, onNew: () => taps++);

    final plusBox = tester.getRect(find.widgetWithIcon(Container, Icons.add));
    // 4px below the very top of the button — the region that used to be dead.
    await tester.tapAt(Offset(plusBox.center.dx, plusBox.top + 4));
    await tester.pump();

    expect(taps, 1);
  });

  testWidgets('tapping the bottom half of the + also works', (tester) async {
    var taps = 0;
    await pumpNav(tester, onNew: () => taps++);

    final plusBox = tester.getRect(find.widgetWithIcon(Container, Icons.add));
    await tester.tapAt(Offset(plusBox.center.dx, plusBox.bottom - 4));
    await tester.pump();

    expect(taps, 1);
  });

  testWidgets('the four destinations still report their index', (tester) async {
    final picked = <int>[];
    await pumpNav(tester, onNew: () {}, onSelect: picked.add);

    for (final label in ['Home', 'Tabs', 'Tasks', 'Settings']) {
      await tester.tap(find.text(label));
      await tester.pump();
    }

    expect(picked, [0, 1, 2, 3]);
  });

  testWidgets('nothing overflows the bar at any common phone width', (tester) async {
    for (final width in [320.0, 360.0, 390.0, 430.0]) {
      tester.view.physicalSize = Size(width * 3, 800 * 3);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);

      await pumpNav(tester, onNew: () {});
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull, reason: 'overflow at ${width}px');
    }
  });
}
