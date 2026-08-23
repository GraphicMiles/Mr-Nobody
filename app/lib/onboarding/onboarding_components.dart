import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

class OnboardingPageLayout extends StatelessWidget {
  final String eyebrow;
  final String title;
  final String? mutedTitle;
  final String description;
  final Widget child;

  const OnboardingPageLayout({
    super.key,
    required this.eyebrow,
    required this.title,
    this.mutedTitle,
    required this.description,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(28, 14, 28, 8),
      child: Column(
        children: [
          Text(
            eyebrow.toUpperCase(),
            style: AppTheme.mono(
              size: 9.5,
              color: AppColors.textMuted,
              w: FontWeight.w600,
              letterSpacing: 1.2,
            ),
          ),
          const SizedBox(height: 9),
          Text.rich(
            TextSpan(
              children: [
                TextSpan(text: title),
                if (mutedTitle != null)
                  TextSpan(
                    text: mutedTitle,
                    style: TextStyle(color: AppColors.textFaint),
                  ),
              ],
            ),
            textAlign: TextAlign.center,
            style: AppTheme.sans(
              size: 25,
              w: FontWeight.w700,
              height: 1.08,
              letterSpacing: -0.85,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            description,
            textAlign: TextAlign.center,
            style: AppTheme.sans(
              size: 12,
              color: AppColors.textFaint,
              height: 1.48,
            ),
          ),
          const SizedBox(height: 15),
          Expanded(child: child),
        ],
      ),
    );
  }
}

class OnboardingCard extends StatelessWidget {
  final Widget child;
  final EdgeInsets padding;
  final Key? surfaceKey;

  const OnboardingCard({
    super.key,
    required this.child,
    this.padding = EdgeInsets.zero,
    this.surfaceKey,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      key: surfaceKey,
      width: double.infinity,
      padding: padding,
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppColors.isWarm ? 18 : 14),
        border: Border.all(color: AppColors.line),
      ),
      clipBehavior: Clip.antiAlias,
      child: child,
    );
  }
}

class OnboardingIconTile extends StatelessWidget {
  final IconData icon;
  final bool selected;

  const OnboardingIconTile(this.icon, {super.key, this.selected = false});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 34,
      height: 34,
      decoration: BoxDecoration(
        color: selected ? AppColors.accent : AppColors.surface2,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Icon(
        icon,
        size: 17,
        color: selected ? AppColors.accentInk : AppColors.textDim,
      ),
    );
  }
}

class OnboardingDivider extends StatelessWidget {
  const OnboardingDivider({super.key});

  @override
  Widget build(BuildContext context) =>
      Divider(height: 1, thickness: 1, color: AppColors.line);
}

class OnboardingCheck extends StatelessWidget {
  final String label;

  const OnboardingCheck(this.label, {super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 17,
            height: 17,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppColors.surface2,
              border: Border.all(color: AppColors.lineStrong),
            ),
            child: Icon(Icons.check_rounded, size: 11, color: AppColors.accent),
          ),
          const SizedBox(width: 9),
          Text(label, style: AppTheme.sans(size: 12, color: AppColors.textDim)),
        ],
      ),
    );
  }
}

class OnboardingStatusTag extends StatelessWidget {
  final String label;
  final bool selected;

  const OnboardingStatusTag(this.label, {super.key, this.selected = false});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: selected ? AppColors.accent : Colors.transparent,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(
          color: selected ? AppColors.accent : AppColors.lineStrong,
        ),
      ),
      child: Text(
        label.toUpperCase(),
        style: AppTheme.mono(
          size: 7.5,
          color: selected ? AppColors.accentInk : AppColors.textMuted,
          w: FontWeight.w600,
        ),
      ),
    );
  }
}
