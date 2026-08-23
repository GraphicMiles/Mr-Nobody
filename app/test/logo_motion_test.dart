import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/widgets/animated_brand_logo.dart';
import 'package:mrnobody/widgets/brand_logo.dart';

void main() {
  test('all seventeen movement definitions return to the exact resting pose', () {
    expect(logoMotionDefinitions.length, 17);
    expect(logoMotionDefinitions.keys.toSet(), LogoMotionType.values.toSet());

    for (final entry in logoMotionDefinitions.entries) {
      expect(entry.value.beats, isNotEmpty, reason: entry.key.label);
      expect(entry.value.finalPose.isResting, isTrue, reason: entry.key.label);
      expect(
        entry.value.duration,
        greaterThan(const Duration(milliseconds: 500)),
        reason: entry.key.label,
      );
      expect(
        entry.value.duration,
        lessThan(const Duration(seconds: 5)),
        reason: entry.key.label,
      );
    }
  });

  test('shuffle bag plays every movement before repeating', () {
    final deck = LogoMotionDeck(random: Random(42));
    final firstCycle = [
      for (var i = 0; i < LogoMotionType.values.length; i++) deck.next(),
    ];
    expect(firstCycle.toSet(), LogoMotionType.values.toSet());

    final firstOfNextCycle = deck.next();
    expect(firstOfNextCycle, isNot(firstCycle.last));
  });

  testWidgets('reduced motion keeps the real brand mark at rest',
      (tester) async {
    final started = <LogoMotionType>[];
    await tester.pumpWidget(
      MaterialApp(
        home: MediaQuery(
          data: const MediaQueryData(disableAnimations: true),
          child: Center(
            child: AnimatedBrandLogo(
              onMotionStarted: started.add,
            ),
          ),
        ),
      ),
    );
    await tester.pump(const Duration(seconds: 2));

    expect(find.byType(BrandLogo), findsOneWidget);
    expect(started, isEmpty);
  });

  testWidgets('active logo advances to another random movement after resting',
      (tester) async {
    final started = <LogoMotionType>[];
    await tester.pumpWidget(
      MaterialApp(
        home: AnimatedBrandLogo(
          deck: LogoMotionDeck(random: Random(7)),
          restDuration: const Duration(milliseconds: 50),
          onMotionStarted: started.add,
        ),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(seconds: 5));
    await tester.pump(const Duration(milliseconds: 60));
    await tester.pump(const Duration(seconds: 5));

    expect(started.length, greaterThanOrEqualTo(2));
    expect(started[0], isNot(started[1]));
  });

  testWidgets('inactive home destination does not run a hidden ticker',
      (tester) async {
    final started = <LogoMotionType>[];
    await tester.pumpWidget(
      MaterialApp(
        home: AnimatedBrandLogo(
          active: false,
          onMotionStarted: started.add,
        ),
      ),
    );
    await tester.pump(const Duration(seconds: 1));
    expect(started, isEmpty);
  });
}
