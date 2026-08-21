import 'dart:math' as math;

import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/brand_logo.dart';

/// First launch — the privacy promise, three guarantees, then the two explicit
/// ways into the product.
///
/// The composition is intentionally editorial rather than a generic centred
/// onboarding card: a warm mark, large statement and quiet ambient cards make
/// the product feel human without adding imagery, network assets or tracking.
class LaunchScreen extends StatelessWidget {
  final VoidCallback onStart;
  final VoidCallback onPrivacy;

  const LaunchScreen(
      {super.key, required this.onStart, required this.onPrivacy});

  @override
  Widget build(BuildContext context) {
    if (!AppColors.isWarm) {
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
                  Text(
                    'Mr Nobody',
                    style: AppTheme.sans(
                      size: 26,
                      w: FontWeight.w800,
                      height: 1.1,
                    ),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    'Tell Mr Nobody what you want from the web.\n'
                    'No ads, no tracking, no history by default.',
                    textAlign: TextAlign.center,
                    style: AppTheme.sans(
                      size: 12.5,
                      color: AppColors.textFaint,
                      height: 1.55,
                    ),
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

    return Scaffold(
      backgroundColor: AppColors.bg,
      body: DecoratedBox(
        decoration: AppTheme.backdrop,
        child: SafeArea(
          child: Stack(
            children: [
              const Positioned(
                  left: -15, top: 120, child: _AmbientCard(angle: -0.18)),
              const Positioned(
                  right: -22, top: 190, child: _AmbientCard(angle: 0.22)),
              Center(
                child: SingleChildScrollView(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 30, vertical: 28),
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 430),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const BrandLogo(size: 58),
                        const SizedBox(height: 24),
                        Text(
                          'PRIVATE BY DEFAULT',
                          style: AppTheme.mono(
                            size: 9.5,
                            color: AppColors.accentSoft,
                            w: FontWeight.w700,
                            letterSpacing: 1.25,
                          ),
                        ),
                        const SizedBox(height: 10),
                        Text(
                          'The web,\nwithout the noise.',
                          style: AppTheme.sans(
                            size: 36,
                            w: FontWeight.w800,
                            height: 0.98,
                            letterSpacing: -1.6,
                          ),
                        ),
                        const SizedBox(height: 17),
                        Text(
                          'Tell Mr Nobody what you want from the web. '
                          'No ads, no tracking, no history by default.',
                          style: AppTheme.sans(
                            size: 13,
                            color: AppColors.textDim,
                            height: 1.55,
                          ),
                        ),
                        const SizedBox(height: 20),
                        const _Check('Ads blocked'),
                        const _Check('Trackers blocked'),
                        const _Check('History is OFF'),
                        const SizedBox(height: 25),
                        _cta('Start browsing', solid: true, onTap: onStart),
                        const SizedBox(height: 10),
                        _cta('Privacy settings',
                            solid: false, onTap: onPrivacy),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _cta(String label,
      {required bool solid, required VoidCallback onTap}) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        width: double.infinity,
        height: AppColors.isWarm ? 49 : 48,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: solid
              ? AppColors.accent
              : AppColors.isWarm
                  ? AppColors.surface
                  : Colors.transparent,
          borderRadius: BorderRadius.circular(999),
          border: solid && !AppColors.isWarm
              ? null
              : Border.all(
                  color: solid ? AppColors.accent : AppColors.lineStrong,
                ),
        ),
        child: Text(
          label,
          style: AppTheme.sans(
            size: 14,
            w: solid || AppColors.isWarm ? FontWeight.w700 : FontWeight.w600,
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
          SizedBox(
            width: AppColors.isWarm ? 16 : 14,
            child: Icon(
              Icons.check,
              size: AppColors.isWarm ? 14 : 13,
              color: AppColors.isWarm ? AppColors.accent : AppColors.text,
            ),
          ),
          const SizedBox(width: 10),
          Text(label,
              style: AppTheme.sans(size: 12.5, color: AppColors.textDim)),
        ],
      ),
    );
  }
}

/// Abstract page card from the approved visual direction. It contains no
/// content and therefore creates no extra information or network surface.
class _AmbientCard extends StatelessWidget {
  final double angle;
  const _AmbientCard({required this.angle});

  @override
  Widget build(BuildContext context) {
    return Transform.rotate(
      angle: angle * math.pi,
      child: Container(
        width: 78,
        height: 104,
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [AppColors.surface3, AppColors.surface],
          ),
          borderRadius: BorderRadius.circular(18),
          border: Border.all(color: AppColors.line),
        ),
      ),
    );
  }
}
