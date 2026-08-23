import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';
import '../onboarding_components.dart';

class DownloadsOnboardingPage extends StatelessWidget {
  final bool active;

  const DownloadsOnboardingPage({super.key, required this.active});

  @override
  Widget build(BuildContext context) {
    return OnboardingPageLayout(
      eyebrow: 'Downloads',
      title: 'Pause. Resume. Verify.',
      description:
          'Save to your chosen folder and recover safely after the app restarts.',
      child: Align(
        alignment: Alignment.topCenter,
        child: OnboardingCard(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _DownloadRow(
                name: 'quarterly-report.pdf',
                size: '4.2 MB',
                start: .18,
                end: active ? .82 : .18,
                state: active ? 'Downloading' : 'Ready',
              ),
              const OnboardingDivider(),
              const _DownloadRow(
                name: 'research-data.csv',
                size: '860 KB',
                start: 1,
                end: 1,
                state: 'Complete',
                complete: true,
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(14, 11, 14, 14),
                child: Row(
                  children: [
                    const Icon(Icons.verified_rounded,
                        size: 15, color: AppColors.success),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Saved to your selected Downloads folder · resume data verified',
                        style:
                            AppTheme.sans(size: 9, color: AppColors.textFaint),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DownloadRow extends StatelessWidget {
  final String name;
  final String size;
  final double start;
  final double end;
  final String state;
  final bool complete;

  const _DownloadRow({
    required this.name,
    required this.size,
    required this.start,
    required this.end,
    required this.state,
    this.complete = false,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 10),
      child: TweenAnimationBuilder<double>(
        tween: Tween(begin: start, end: end),
        duration: const Duration(seconds: 4),
        curve: Curves.easeOutCubic,
        builder: (context, progress, _) {
          return Column(
            children: [
              Row(
                children: [
                  OnboardingIconTile(
                    complete ? Icons.check_rounded : Icons.download_rounded,
                    selected: complete,
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: AppTheme.sans(size: 11, w: FontWeight.w600)),
                  ),
                  Text(size,
                      style:
                          AppTheme.mono(size: 8, color: AppColors.textMuted)),
                ],
              ),
              const SizedBox(height: 9),
              ClipRRect(
                borderRadius: BorderRadius.circular(99),
                child: LinearProgressIndicator(
                  value: progress,
                  minHeight: 4,
                  color: AppColors.accent,
                  backgroundColor: AppColors.surface2,
                ),
              ),
              const SizedBox(height: 7),
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    progress >= .82 && !complete
                        ? 'Paused · tap to resume'
                        : state,
                    style: AppTheme.mono(size: 8, color: AppColors.textMuted),
                  ),
                  Text('${(progress * 100).round()}%',
                      style:
                          AppTheme.mono(size: 8, color: AppColors.textMuted)),
                ],
              ),
            ],
          );
        },
      ),
    );
  }
}
