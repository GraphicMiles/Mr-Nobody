import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/onboarding/onboarding_screen.dart';
import 'package:mrnobody/onboarding/pages/agent_onboarding_page.dart';
import 'package:mrnobody/theme/app_theme.dart';
import 'package:mrnobody/widgets/bottom_nav.dart';

void main() {
  Widget app({
    required VoidCallback onStart,
    VoidCallback? onPrivacy,
    bool accessibleNavigation = true,
    Duration pageDuration = kOnboardingPageDuration,
  }) {
    return MaterialApp(
      theme: AppTheme.dark(),
      home: MediaQuery(
        data: MediaQueryData(accessibleNavigation: accessibleNavigation),
        child: OnboardingScreen(
          onStart: onStart,
          onPrivacy: onPrivacy ?? () {},
          pageDuration: pageDuration,
        ),
      ),
    );
  }

  Future<void> jumpToPage(WidgetTester tester, int page) async {
    final pageView =
        tester.widget<PageView>(find.byKey(kOnboardingPageViewKey));
    pageView.controller!.jumpToPage(page);
    await tester.pump();
  }

  testWidgets('onboarding starts on page one without the app bottom bar',
      (tester) async {
    await tester.pumpWidget(app(onStart: () {}));
    await tester.pump();

    expect(find.byKey(kOnboardingPageViewKey), findsOneWidget);
    expect(find.text('Mr Nobody'), findsOneWidget);
    expect(find.byType(BottomNav), findsNothing);
    expect(find.byKey(kOnboardingSkipKey), findsOneWidget);
  });

  testWidgets('skip exits onboarding immediately', (tester) async {
    var starts = 0;
    await tester.pumpWidget(app(onStart: () => starts++));
    await tester.tap(find.byKey(kOnboardingSkipKey));
    await tester.pump();
    expect(starts, 1);
  });

  testWidgets('swipe advances and Back returns to the previous page',
      (tester) async {
    await tester.pumpWidget(app(onStart: () {}));
    await tester.drag(
        find.byKey(kOnboardingPageViewKey), const Offset(-700, 0));
    for (var i = 0; i < 12; i++) {
      await tester.pump(const Duration(milliseconds: 80));
    }

    var pageView = tester.widget<PageView>(find.byKey(kOnboardingPageViewKey));
    expect(pageView.controller!.page, closeTo(1, .01));
    await tester.tap(find.byKey(kOnboardingBackKey));
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 60));
    }
    pageView = tester.widget<PageView>(find.byKey(kOnboardingPageViewKey));
    expect(pageView.controller!.page, closeTo(0, .01));
  });

  testWidgets('page advances automatically after five seconds', (tester) async {
    await tester.pumpWidget(app(
      onStart: () {},
      accessibleNavigation: false,
    ));
    await tester.pump(kOnboardingPageDuration);
    await tester.pump(const Duration(milliseconds: 400));
    final pageView =
        tester.widget<PageView>(find.byKey(kOnboardingPageViewKey));
    expect(pageView.controller!.page, closeTo(1, .01));
  });

  testWidgets('agent pipeline icons and answer stay inside their card',
      (tester) async {
    await tester.pumpWidget(app(onStart: () {}));
    await jumpToPage(tester, 2);
    await tester.pump(const Duration(milliseconds: 2400));

    final card = tester.getRect(find.byKey(kAgentPipelineCardKey));
    final search = tester.getRect(find.byKey(kAgentSearchStepKey));
    final read = tester.getRect(find.byKey(kAgentReadStepKey));
    final answer = tester.getRect(find.byKey(kAgentAnswerKey));
    for (final rect in [search, read, answer]) {
      expect(rect.left, greaterThanOrEqualTo(card.left));
      expect(rect.right, lessThanOrEqualTo(card.right));
      expect(rect.top, greaterThanOrEqualTo(card.top));
      expect(rect.bottom, lessThanOrEqualTo(card.bottom));
    }
  });

  testWidgets('the final Start browsing action exits onboarding',
      (tester) async {
    var starts = 0;
    await tester.pumpWidget(app(onStart: () => starts++));

    await jumpToPage(tester, 6);

    expect(find.text('Start browsing'), findsOneWidget);
    await tester.tap(find.text('Start browsing'));
    await tester.pump();
    expect(starts, 1);
  });
}
