import 'dart:async';

import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';
import '../onboarding_components.dart';

const Key kAgentPipelineCardKey = Key('agent-pipeline-card');
const Key kAgentSearchStepKey = Key('agent-search-step');
const Key kAgentReadStepKey = Key('agent-read-step');
const Key kAgentAnswerKey = Key('agent-answer');

class AgentOnboardingPage extends StatefulWidget {
  final bool active;

  const AgentOnboardingPage({super.key, required this.active});

  @override
  State<AgentOnboardingPage> createState() => _AgentOnboardingPageState();
}

class _AgentOnboardingPageState extends State<AgentOnboardingPage> {
  final _timers = <Timer>[];
  var _phase = 0;

  @override
  void initState() {
    super.initState();
    if (widget.active) _start();
  }

  @override
  void didUpdateWidget(AgentOnboardingPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active && !oldWidget.active) _start();
    if (!widget.active && oldWidget.active) _cancelTimers();
  }

  void _start() {
    _cancelTimers();
    setState(() => _phase = 0);
    _timers
      ..add(Timer(const Duration(milliseconds: 850), () => _setPhase(1)))
      ..add(Timer(const Duration(milliseconds: 1650), () => _setPhase(2)))
      ..add(Timer(const Duration(milliseconds: 2250), () => _setPhase(3)));
  }

  void _setPhase(int phase) {
    if (mounted && widget.active) setState(() => _phase = phase);
  }

  void _cancelTimers() {
    for (final timer in _timers) {
      timer.cancel();
    }
    _timers.clear();
  }

  @override
  void dispose() {
    _cancelTimers();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return OnboardingPageLayout(
      eyebrow: 'Agent',
      title: 'Ask once. See the sources.',
      description:
          'It searches a bounded set of pages, reads what matters and shows its work.',
      child: Column(
        children: [
          _prompt(),
          const SizedBox(height: 10),
          OnboardingCard(
            surfaceKey: kAgentPipelineCardKey,
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                _pipelineStep(
                  rowKey: kAgentSearchStepKey,
                  icon: Icons.search_rounded,
                  title: 'Search the web',
                  subtitle: 'Finding useful, relevant results',
                  complete: _phase >= 1,
                ),
                const OnboardingDivider(),
                _pipelineStep(
                  rowKey: kAgentReadStepKey,
                  icon: Icons.menu_book_rounded,
                  title: 'Read three pages',
                  subtitle: 'Extracting prices, stock and specifications',
                  complete: _phase >= 2,
                ),
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 240),
                  curve: Curves.easeOutCubic,
                  opacity: _phase >= 3 ? 1 : 0,
                  child: _answer(),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _prompt() {
    return Container(
      height: 48,
      padding: const EdgeInsets.symmetric(horizontal: 14),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.lineStrong),
      ),
      child: Row(
        children: [
          Icon(Icons.auto_awesome_rounded, size: 16, color: AppColors.textDim),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              'Find the best laptop under ₦500k',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: AppTheme.sans(size: 11.5, color: AppColors.textDim),
            ),
          ),
        ],
      ),
    );
  }

  Widget _pipelineStep({
    required Key rowKey,
    required IconData icon,
    required String title,
    required String subtitle,
    required bool complete,
  }) {
    return Padding(
      key: rowKey,
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Row(
        children: [
          AnimatedContainer(
            duration: const Duration(milliseconds: 220),
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: complete ? AppColors.accent : AppColors.surface2,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(
                color: complete ? AppColors.accent : AppColors.lineStrong,
              ),
            ),
            child: Icon(
              complete ? Icons.check_rounded : icon,
              size: 16,
              color: complete ? AppColors.accentInk : AppColors.textFaint,
            ),
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: AppTheme.sans(
                        size: 11.5,
                        color: complete ? AppColors.text : AppColors.textDim,
                        w: FontWeight.w600)),
                const SizedBox(height: 3),
                Text(subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: AppTheme.sans(size: 9, color: AppColors.textMuted)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _answer() {
    return Container(
      key: kAgentAnswerKey,
      width: double.infinity,
      margin: const EdgeInsets.only(top: 4),
      padding: const EdgeInsets.only(top: 11, bottom: 4),
      decoration: BoxDecoration(
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text.rich(
            TextSpan(
              children: [
                const TextSpan(text: 'Top pick: '),
                TextSpan(
                  text: 'RedmiBook 14 — ₦498,000',
                  style: TextStyle(
                    color: AppColors.text,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ],
            ),
            style: AppTheme.sans(size: 10.5, color: AppColors.textDim),
          ),
          const SizedBox(height: 4),
          Text(
            '16 GB RAM, 512 GB SSD and available from three stores.',
            style: AppTheme.sans(size: 9.5, color: AppColors.textFaint),
          ),
          const SizedBox(height: 9),
          Text('SOURCES',
              style: AppTheme.mono(
                  size: 8,
                  color: AppColors.textMuted,
                  w: FontWeight.w600,
                  letterSpacing: 1.0)),
          const SizedBox(height: 5),
          _source('retailers.com', 'Current price and stock'),
          _source('techng.com', 'August laptop comparison'),
        ],
      ),
    );
  }

  Widget _source(String domain, String detail) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 5),
      child: Row(
        children: [
          Icon(Icons.link_rounded, size: 12, color: AppColors.textMuted),
          const SizedBox(width: 6),
          Text(domain,
              style: AppTheme.sans(
                  size: 9, color: AppColors.textDim, w: FontWeight.w600)),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              detail,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: AppTheme.sans(size: 8.5, color: AppColors.textMuted),
            ),
          ),
        ],
      ),
    );
  }
}
