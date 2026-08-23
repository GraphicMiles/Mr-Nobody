import 'package:flutter/material.dart';

import '../onboarding/onboarding_screen.dart';

/// Compatibility entry point for the first-launch route.
///
/// The implementation lives in `lib/onboarding/` so navigation, shared
/// components and each of the seven pages remain independently testable.
class LaunchScreen extends StatelessWidget {
  final VoidCallback onStart;
  final VoidCallback onPrivacy;

  const LaunchScreen({
    super.key,
    required this.onStart,
    required this.onPrivacy,
  });

  @override
  Widget build(BuildContext context) => OnboardingScreen(
        onStart: onStart,
        onPrivacy: onPrivacy,
      );
}
