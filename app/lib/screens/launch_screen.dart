import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/brand_logo.dart';

/// First launch (S1) — brand mark, the privacy promise, the three guarantees,
/// then "Start browsing" / "Privacy settings". Matches the wireframe's
/// `#v-launch` view: 44px logo, 26px wordmark, mono-free body copy, pill CTAs.
class LaunchScreen extends StatelessWidget {
  final VoidCallback onStart;
  final VoidCallback onPrivacy;

  const LaunchScreen({super.key, required this.onStart, required this.onPrivacy});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const BrandLogo(size: 44),
                const SizedBox(height: 16),
                Text('Mr Nobody', style: AppTheme.sans(size: 26, w: FontWeight.w800, height: 1.1)),
                const SizedBox(height: 10),
                Text(
                  'Tell Mr Nobody what you want from the web.\n'
                  'No ads, no tracking, no history by default.',
                  textAlign: TextAlign.center,
                  style: AppTheme.sans(size: 12.5, color: AppColors.textFaint, height: 1.55),
                ),
                const SizedBox(height: 22),
                const _Check('Ads blocked'),
                const _Check('Trackers blocked'),
                const _Check('History is OFF'),
                const SizedBox(height: 24),
                _cta('Start browsing', solid: true, onTap: onStart),
                const SizedBox(height: 10),
                _cta('Privacy settings', solid: false, onTap: onPrivacy),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _cta(String label, {required bool solid, required VoidCallback onTap}) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        width: double.infinity,
        height: 48,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: solid ? AppColors.accent : Colors.transparent,
          borderRadius: BorderRadius.circular(999),
          border: solid ? null : Border.all(color: AppColors.lineStrong),
        ),
        child: Text(
          label,
          style: AppTheme.sans(
            size: 14,
            w: solid ? FontWeight.w700 : FontWeight.w600,
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
      padding: const EdgeInsets.symmetric(vertical: 7),
      child: Row(
        children: [
          const SizedBox(width: 14, child: Icon(Icons.check, size: 13, color: AppColors.text)),
          const SizedBox(width: 10),
          Text(label, style: AppTheme.sans(size: 12.5, color: AppColors.textDim)),
        ],
      ),
    );
  }
}
