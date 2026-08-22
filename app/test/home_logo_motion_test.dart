import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/screens/home_screen.dart';
import 'package:mrnobody/widgets/animated_brand_logo.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('mrnobody/core');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'recentTasks') return <Map<String, Object>>[];
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('home uses the lower animated hero without decorative page boxes',
      (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: MediaQuery(
          data: const MediaQueryData(disableAnimations: true),
          child: HomeScreen(
            isActive: false,
            onSubmit: (_) {},
            onShortcut: (_) {},
            onOpenTask: (_) {},
          ),
        ),
      ),
    );
    await tester.pump();

    expect(find.byType(AnimatedBrandLogo), findsOneWidget);
    final hero = tester.getRect(find.byKey(kHomeLogoHeroKey));
    final search = tester.getRect(find.byKey(kHomeSearchPillKey));
    expect(hero.height, 190);
    expect(search.top, greaterThanOrEqualTo(hero.bottom));
  });
}
