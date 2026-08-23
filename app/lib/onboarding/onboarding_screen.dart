import 'dart:async';

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import 'pages/agent_onboarding_page.dart';
import 'pages/browser_onboarding_page.dart';
import 'pages/downloads_onboarding_page.dart';
import 'pages/open_source_onboarding_page.dart';
import 'pages/privacy_onboarding_page.dart';
import 'pages/ready_onboarding_page.dart';
import 'pages/welcome_onboarding_page.dart';

const Key kOnboardingPageViewKey = Key('onboarding-page-view');
const Key kOnboardingBackKey = Key('onboarding-back');
const Key kOnboardingSkipKey = Key('onboarding-skip');
const Duration kOnboardingPageDuration = Duration(seconds: 5);

class OnboardingScreen extends StatefulWidget {
  final VoidCallback onStart;
  final VoidCallback onPrivacy;
  final Duration pageDuration;

  const OnboardingScreen({
    super.key,
    required this.onStart,
    required this.onPrivacy,
    this.pageDuration = kOnboardingPageDuration,
  });

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen>
    with WidgetsBindingObserver {
  static const _pageCount = 7;

  final _controller = PageController();
  Timer? _pageTimer;
  var _page = 0;
  var _autoAdvanceAllowed = true;
  var _resumed = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) => _scheduleNext());
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final media = MediaQuery.maybeOf(context);
    _autoAdvanceAllowed = media?.accessibleNavigation != true;
    if (!_autoAdvanceAllowed) _pageTimer?.cancel();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _resumed = state == AppLifecycleState.resumed;
    if (_resumed) {
      _scheduleNext();
    } else {
      _pageTimer?.cancel();
    }
  }

  void _scheduleNext() {
    _pageTimer?.cancel();
    if (!mounted ||
        !_resumed ||
        !_autoAdvanceAllowed ||
        _page >= _pageCount - 1) {
      return;
    }
    _pageTimer = Timer(widget.pageDuration, _next);
  }

  void _next() {
    if (_page >= _pageCount - 1 || !_controller.hasClients) return;
    _controller.nextPage(
      duration: const Duration(milliseconds: 360),
      curve: Curves.easeOutCubic,
    );
  }

  void _back() {
    if (_page == 0 || !_controller.hasClients) return;
    _controller.previousPage(
      duration: const Duration(milliseconds: 320),
      curve: Curves.easeOutCubic,
    );
  }

  void _onPageChanged(int page) {
    setState(() => _page = page);
    _scheduleNext();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pageTimer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: DecoratedBox(
        decoration: AppTheme.backdrop,
        child: SafeArea(
          child: Column(
            children: [
              _TopBar(
                canGoBack: _page > 0,
                onBack: _back,
                onSkip: widget.onStart,
              ),
              Expanded(
                child: GestureDetector(
                  behavior: HitTestBehavior.translucent,
                  onTap: _next,
                  child: PageView(
                    key: kOnboardingPageViewKey,
                    controller: _controller,
                    onPageChanged: _onPageChanged,
                    children: [
                      WelcomeOnboardingPage(active: _page == 0),
                      BrowserOnboardingPage(active: _page == 1),
                      AgentOnboardingPage(active: _page == 2),
                      PrivacyOnboardingPage(
                        onOpenPrivacy: widget.onPrivacy,
                      ),
                      DownloadsOnboardingPage(active: _page == 4),
                      const OpenSourceOnboardingPage(),
                      ReadyOnboardingPage(
                        active: _page == 6,
                        onStart: widget.onStart,
                      ),
                    ],
                  ),
                ),
              ),
              _Progress(page: _page, count: _pageCount),
            ],
          ),
        ),
      ),
    );
  }
}

class _TopBar extends StatelessWidget {
  final bool canGoBack;
  final VoidCallback onBack;
  final VoidCallback onSkip;

  const _TopBar({
    required this.canGoBack,
    required this.onBack,
    required this.onSkip,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 48,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 18),
        child: Row(
          children: [
            Expanded(
              child: Align(
                alignment: Alignment.centerLeft,
                child: IgnorePointer(
                  ignoring: !canGoBack,
                  child: AnimatedOpacity(
                    duration: const Duration(milliseconds: 180),
                    opacity: canGoBack ? 1 : 0,
                    child: TextButton.icon(
                      key: kOnboardingBackKey,
                      onPressed: onBack,
                      icon: const Icon(Icons.chevron_left_rounded, size: 17),
                      label: const Text('Back'),
                      style: TextButton.styleFrom(
                        foregroundColor: AppColors.textFaint,
                        padding: EdgeInsets.zero,
                        textStyle: AppTheme.sans(size: 11.5),
                      ),
                    ),
                  ),
                ),
              ),
            ),
            TextButton(
              key: kOnboardingSkipKey,
              onPressed: onSkip,
              style: TextButton.styleFrom(
                foregroundColor: AppColors.textFaint,
                padding: EdgeInsets.zero,
                textStyle: AppTheme.sans(size: 11.5),
              ),
              child: const Text('Skip'),
            ),
          ],
        ),
      ),
    );
  }
}

class _Progress extends StatelessWidget {
  final int page;
  final int count;

  const _Progress({required this.page, required this.count});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 48,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          for (var i = 0; i < count; i++)
            AnimatedContainer(
              duration: const Duration(milliseconds: 260),
              curve: Curves.easeOutCubic,
              width: i == page ? 20 : 6,
              height: 6,
              margin: const EdgeInsets.symmetric(horizontal: 3),
              decoration: BoxDecoration(
                color: i == page ? AppColors.accent : AppColors.surface3,
                borderRadius: BorderRadius.circular(99),
              ),
            ),
        ],
      ),
    );
  }
}
