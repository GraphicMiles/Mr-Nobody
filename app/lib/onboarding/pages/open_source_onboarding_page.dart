import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';
import '../onboarding_components.dart';

class OpenSourceOnboardingPage extends StatelessWidget {
  const OpenSourceOnboardingPage({super.key});

  @override
  Widget build(BuildContext context) {
    return OnboardingPageLayout(
      eyebrow: 'Open source',
      title: 'Inspect it. Improve it.',
      description:
          'Read the code, report an issue or contribute a fix under the MIT License.',
      child: Align(
        alignment: Alignment.topCenter,
        child: OnboardingCard(
          padding: const EdgeInsets.all(14),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                children: [
                  const OnboardingIconTile(Icons.code_rounded, selected: true),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('GraphicMiles / Mr-Nobody',
                            style:
                                AppTheme.sans(size: 11.5, w: FontWeight.w700)),
                        const SizedBox(height: 3),
                        Text('PUBLIC · FLUTTER + JAVA',
                            style: AppTheme.mono(
                                size: 8, color: AppColors.textMuted)),
                      ],
                    ),
                  ),
                  Icon(Icons.open_in_new_rounded,
                      size: 15, color: AppColors.textFaint),
                ],
              ),
              Container(
                width: double.infinity,
                margin: const EdgeInsets.only(top: 11),
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: AppColors.bg,
                  borderRadius: BorderRadius.circular(11),
                  border: Border.all(color: AppColors.line),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _diff('- tracking enabled by default', AppColors.danger),
                    _diff('+ history disabled by default', AppColors.success),
                    _diff('+ consequential actions require approval',
                        AppColors.success),
                  ],
                ),
              ),
              const SizedBox(height: 9),
              GridView.count(
                crossAxisCount: 2,
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                mainAxisSpacing: 7,
                crossAxisSpacing: 7,
                childAspectRatio: 3.25,
                children: const [
                  _Contribution(Icons.code_rounded, 'View source'),
                  _Contribution(Icons.bug_report_outlined, 'Report issue'),
                  _Contribution(Icons.route_outlined, 'Read roadmap'),
                  _Contribution(Icons.call_merge_rounded, 'Open pull request'),
                ],
              ),
              const SizedBox(height: 9),
              Text('OPEN SOURCE · MIT LICENSE',
                  style: AppTheme.mono(
                      size: 8, color: AppColors.textMuted, letterSpacing: .8)),
            ],
          ),
        ),
      ),
    );
  }

  Widget _diff(String text, Color color) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Text(text, style: AppTheme.mono(size: 8, color: color)),
    );
  }
}

class _Contribution extends StatelessWidget {
  final IconData icon;
  final String label;

  const _Contribution(this.icon, this.label);

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.line),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, size: 13, color: AppColors.textFaint),
          const SizedBox(width: 6),
          Flexible(
            child: Text(label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: AppTheme.sans(size: 8.5, color: AppColors.textDim)),
          ),
        ],
      ),
    );
  }
}
