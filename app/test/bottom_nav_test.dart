import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/theme/app_theme.dart';
import 'package:mrnobody/widgets/bottom_nav.dart';

import 'test_fonts.dart';

/// The "+" used to be drawn with a negative offset, which put its top half
/// outside the bar's box: clipped when painting and invisible to hit-testing.
/// It now sits in the row with the other items — no overhang, so the bar also
/// stops reserving an empty strip over the content behind it.
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

  testWidgets('the + sits inside the bar, aligned with the other items', (tester) async {
    await pumpNav(tester, onNew: () {});

    final navBox = tester.getRect(find.byType(BottomNav));
    final plusBox = tester.getRect(find.byKey(kNavNewButtonKey));

    expect(plusBox.top, greaterThanOrEqualTo(navBox.top),
        reason: 'the + must not be painted above its parent, or it gets clipped');
    expect(plusBox.bottom, lessThanOrEqualTo(navBox.bottom));

    // Optically on the same row as the destinations: its centre lines up with
    // the icon/label block of a neighbouring item.
    final homeBox = tester.getRect(find.text('Home'));
    final tasksBox = tester.getRect(find.text('Tasks'));
    expect(plusBox.center.dx, closeTo(navBox.center.dx, 1));
    expect(plusBox.bottom, lessThanOrEqualTo(homeBox.bottom + 6));
    expect(plusBox.top, greaterThanOrEqualTo(navBox.top));
    expect(tasksBox.center.dx, greaterThan(plusBox.center.dx));
  });

  testWidgets('the bar reserves no empty strip above itself', (tester) async {
    await pumpNav(tester, onNew: () {});

    final navBox = tester.getRect(find.byType(BottomNav));
    final surface = tester.getRect(find.descendant(
      of: find.byType(BottomNav),
      matching: find.byWidgetPredicate((w) =>
          w is Container && w.decoration is BoxDecoration &&
          (w.decoration as BoxDecoration).border != null),
    ).first);

    // The painted bar starts at the widget's top edge: anything above it would
    // be dead space covering the page underneath.
    expect(surface.top, closeTo(navBox.top, 0.5));
  });

  testWidgets('tapping the TOP edge of the + still creates a tab', (tester) async {
    var taps = 0;
    await pumpNav(tester, onNew: () => taps++);

    final plusBox = tester.getRect(find.byKey(kNavNewButtonKey));
    // 4px below the very top of the button — the region that used to be dead.
    await tester.tapAt(Offset(plusBox.center.dx, plusBox.top + 4));
    await tester.pump();

    expect(taps, 1);
  });

  testWidgets('tapping the bottom half of the + also works', (tester) async {
    var taps = 0;
    await pumpNav(tester, onNew: () => taps++);

    final plusBox = tester.getRect(find.byKey(kNavNewButtonKey));
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
