import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';
import '../onboarding_components.dart';

class PrivacyOnboardingPage extends StatelessWidget {
  final VoidCallback onOpenPrivacy;

  const PrivacyOnboardingPage({
    super.key,
    required this.onOpenPrivacy,
  });

  @override
  Widget build(BuildContext context) {
    return OnboardingPageLayout(
      eyebrow: 'Privacy',
      title: 'Stay out of sight.',
      description:
          'Normal, Private or Nobody. Protection is explicit and a promised route fails closed.',
      child: Align(
        alignment: Alignment.topCenter,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            OnboardingCard(
              child: Column(
                children: [
                  _mode(
                    icon: Icons.shield_outlined,
                    title: 'Normal',
                    detail: 'Ads and trackers blocked',
                    tag: 'default',
                  ),
                  const OnboardingDivider(),
                  _mode(
                    icon: Icons.visibility_off_outlined,
                    title: 'Private',
                    detail: 'Clears local data with the tab',
                    tag: 'per tab',
                  ),
                  const OnboardingDivider(),
                  _mode(
                    icon: Icons.verified_user_outlined,
                    title: 'Nobody',
                    detail: 'Uses your configured privacy route',
                    tag: 'tor',
                    selected: true,
                  ),
                  const OnboardingDivider(),
                  const _RouteStrip(),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(14, 0, 14, 13),
                    child: Text(
                      'Your ISP sees an encrypted tunnel, not where you go.',
                      style:
                          AppTheme.sans(size: 10, color: AppColors.textFaint),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 10),
            TextButton.icon(
              onPressed: onOpenPrivacy,
              icon: const Icon(Icons.tune_rounded, size: 15),
              label: const Text('Open privacy settings'),
              style: TextButton.styleFrom(
                foregroundColor: AppColors.textDim,
                textStyle: AppTheme.sans(size: 11, w: FontWeight.w600),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _mode({
    required IconData icon,
    required String title,
    required String detail,
    required String tag,
    bool selected = false,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 10),
      child: Row(
        children: [
          OnboardingIconTile(icon, selected: selected),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: AppTheme.sans(size: 11.5, w: FontWeight.w600)),
                const SizedBox(height: 3),
                Text(detail,
                    style: AppTheme.sans(size: 9, color: AppColors.textFaint)),
              ],
            ),
          ),
          OnboardingStatusTag(tag, selected: selected),
        ],
      ),
    );
  }
}

class _RouteStrip extends StatelessWidget {
  const _RouteStrip();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 10),
      child: Row(
        children: [
          Text('YOU',
              style: AppTheme.mono(size: 8, color: AppColors.textMuted)),
          Expanded(child: Divider(color: AppColors.lineStrong, indent: 8)),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Icon(Icons.lock_outline_rounded,
                size: 13, color: AppColors.textDim),
          ),
          Expanded(child: Divider(color: AppColors.lineStrong, endIndent: 8)),
          Text('WEB',
              style: AppTheme.mono(size: 8, color: AppColors.textMuted)),
        ],
      ),
    );
  }
}
