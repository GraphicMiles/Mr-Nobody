import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';
import '../../widgets/animated_brand_logo.dart';

class ReadyOnboardingPage extends StatelessWidget {
  final bool active;
  final VoidCallback onStart;

  const ReadyOnboardingPage({
    super.key,
    required this.active,
    required this.onStart,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(30, 8, 30, 18),
      child: Column(
        children: [
          Expanded(
            child: Center(
              child: AnimatedBrandLogo(
                size: 100,
                active: active,
                restDuration: const Duration(milliseconds: 900),
              ),
            ),
          ),
          Container(
            height: 48,
            padding: const EdgeInsets.symmetric(horizontal: 16),
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: AppColors.lineStrong),
            ),
            child: Row(
              children: [
                Icon(Icons.search_rounded,
                    size: 16, color: AppColors.textFaint),
                const SizedBox(width: 10),
                Text('Ask Mr Nobody anything…',
                    style: AppTheme.sans(size: 13, color: AppColors.textFaint)),
              ],
            ),
          ),
          const SizedBox(height: 20),
          GestureDetector(
            onTap: onStart,
            behavior: HitTestBehavior.opaque,
            child: Container(
              width: double.infinity,
              height: 50,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: AppColors.accent,
                borderRadius: BorderRadius.circular(14),
              ),
              child: Text(
                'Start browsing',
                style: AppTheme.sans(
                  size: 14,
                  color: AppColors.accentInk,
                  w: FontWeight.w700,
                ),
              ),
            ),
          ),
          const SizedBox(height: 14),
          Text(
            'You are ready.\nEvery feature stays one tap away in Settings.',
            textAlign: TextAlign.center,
            style: AppTheme.sans(
              size: 10.5,
              color: AppColors.textMuted,
              height: 1.55,
            ),
          ),
        ],
      ),
    );
  }
}
