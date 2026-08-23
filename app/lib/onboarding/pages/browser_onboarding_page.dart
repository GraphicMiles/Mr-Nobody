import 'dart:async';

import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';
import '../onboarding_components.dart';

class BrowserOnboardingPage extends StatefulWidget {
  final bool active;

  const BrowserOnboardingPage({super.key, required this.active});

  @override
  State<BrowserOnboardingPage> createState() => _BrowserOnboardingPageState();
}

class _BrowserOnboardingPageState extends State<BrowserOnboardingPage> {
  static const _query = 'private browser';
  Timer? _timer;
  var _characters = 0;

  @override
  void initState() {
    super.initState();
    if (widget.active) _startTyping();
  }

  @override
  void didUpdateWidget(BrowserOnboardingPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active && !oldWidget.active) _startTyping();
    if (!widget.active && oldWidget.active) _timer?.cancel();
  }

  void _startTyping() {
    _timer?.cancel();
    setState(() => _characters = 0);
    _timer = Timer.periodic(const Duration(milliseconds: 70), (timer) {
      if (!mounted || _characters >= _query.length) {
        timer.cancel();
        return;
      }
      setState(() => _characters++);
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return OnboardingPageLayout(
      eyebrow: 'Browsing',
      title: 'Search your way.',
      description:
          'Open a URL, use your chosen search engine and keep every tab ready.',
      child: Align(
        alignment: Alignment.topCenter,
        child: OnboardingCard(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _addressBar(),
              _result(
                'mrnobody.app',
                'A private browser with an agent inside',
                'Search, read and compare without ad-tech following along.',
              ),
              const OnboardingDivider(),
              _result(
                'docs.mrnobody.app',
                'Tabs, search engines and commands',
                'Switch quickly without losing the page you were using.',
              ),
              const OnboardingDivider(),
              _result(
                'github.com/GraphicMiles',
                'Inspect Mr Nobody’s source code',
                'Open source under the MIT License.',
              ),
              _browserBar(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _addressBar() {
    final query = _query.substring(0, _characters);
    return Container(
      height: 46,
      margin: const EdgeInsets.all(11),
      padding: const EdgeInsets.symmetric(horizontal: 13),
      decoration: BoxDecoration(
        color: AppColors.surface2,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.line),
      ),
      child: Row(
        children: [
          Icon(Icons.search_rounded, size: 15, color: AppColors.textFaint),
          const SizedBox(width: 9),
          Expanded(
            child: Text.rich(
              TextSpan(
                children: [
                  const TextSpan(
                    text: 'duckduckgo.com/?q=',
                    style: TextStyle(fontWeight: FontWeight.w600),
                  ),
                  TextSpan(text: query),
                ],
              ),
              maxLines: 1,
              overflow: TextOverflow.clip,
              style: AppTheme.sans(size: 10.5, color: AppColors.textFaint),
            ),
          ),
        ],
      ),
    );
  }

  Widget _result(String domain, String title, String detail) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(domain,
              style: AppTheme.mono(size: 7.5, color: AppColors.textMuted)),
          const SizedBox(height: 3),
          Text(title, style: AppTheme.sans(size: 11, w: FontWeight.w600)),
          const SizedBox(height: 4),
          Text(
            detail,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: AppTheme.sans(size: 8.5, color: AppColors.textFaint),
          ),
        ],
      ),
    );
  }

  Widget _browserBar() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 8, 14, 11),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Icon(Icons.arrow_back_rounded, size: 16, color: AppColors.textFaint),
          Icon(Icons.arrow_forward_rounded,
              size: 16, color: AppColors.textMuted),
          Container(
            width: 30,
            height: 30,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppColors.accent,
            ),
            child:
                Icon(Icons.add_rounded, size: 17, color: AppColors.accentInk),
          ),
          Icon(Icons.layers_rounded, size: 16, color: AppColors.textFaint),
          Icon(Icons.more_vert_rounded, size: 16, color: AppColors.textFaint),
        ],
      ),
    );
  }
}
