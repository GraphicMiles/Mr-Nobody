import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// First launch — logo, promise, Start Browsing / Privacy settings.
class LaunchScreen extends StatelessWidget {
  final VoidCallback onStart;
  const LaunchScreen({super.key, required this.onStart});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Text(
                'Mr Nobody',
                style: AppTheme.sans(size: 28, w: FontWeight.w800),
              ),
              const SizedBox(height: 10),
              Text(
                'Tell Mr Nobody what you want from the web.\nNo ads, no tracking, no history by default.',
                textAlign: TextAlign.center,
                style: AppTheme.sans(size: 13, color: AppColors.textDim, w: FontWeight.w400),
              ),
              const SizedBox(height: 24),
              const _Check('Ads blocked'),
              const _Check('Trackers blocked'),
              const _Check('History is OFF'),
              const SizedBox(height: 24),
              SizedBox(
                width: double.infinity,
                child: _cta(context, 'Start browsing', true, onStart),
              ),
              const SizedBox(height: 10),
              SizedBox(
                width: double.infinity,
                child: _cta(context, 'Privacy settings', false, () {}),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _cta(BuildContext c, String label, bool solid, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        height: 48,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: solid ? AppColors.accent : Colors.transparent,
          borderRadius: BorderRadius.circular(24),
          border: solid ? null : Border.all(color: AppColors.lineStrong),
        ),
        child: Text(
          label,
          style: AppTheme.sans(
            size: 14,
            w: FontWeight.w700,
            color: solid ? AppColors.accentInk : AppColors.textDim,
          ),
        ),
      ),
    );
  }
}

class _Check extends StatelessWidget {
  final String label;
  const _Check(this.label);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        children: [
          const Icon(Icons.check, size: 16, color: AppColors.text),
          const SizedBox(width: 10),
          Text(label, style: AppTheme.sans(size: 13, color: AppColors.textDim)),
        ],
      ),
    );
  }
}
