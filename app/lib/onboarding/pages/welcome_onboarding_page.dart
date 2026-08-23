import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';
import '../../widgets/animated_brand_logo.dart';
import '../onboarding_components.dart';

class WelcomeOnboardingPage extends StatelessWidget {
  final bool active;

  const WelcomeOnboardingPage({super.key, required this.active});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(28, 8, 28, 18),
      child: Column(
        children: [
          Expanded(
            child: Center(
              child: AnimatedBrandLogo(
                size: 104,
                active: active,
                restDuration: const Duration(milliseconds: 900),
              ),
            ),
          ),
          Text(
            'Mr Nobody',
            style: AppTheme.sans(
              size: 26,
              w: FontWeight.w800,
              height: 1.08,
              letterSpacing: -1.05,
            ),
          ),
          const SizedBox(height: 9),
          Text(
            'Tell Mr Nobody what you want from the web.\n'
            'No ads, no tracking, no history by default.',
            textAlign: TextAlign.center,
            style: AppTheme.sans(
              size: 12.5,
              color: AppColors.textFaint,
              height: 1.5,
            ),
          ),
          const SizedBox(height: 16),
          const OnboardingCheck('Browse the real web'),
          const OnboardingCheck('Ask an agent with sources'),
          const OnboardingCheck('Inspect the open-source code'),
        ],
      ),
    );
  }
}
