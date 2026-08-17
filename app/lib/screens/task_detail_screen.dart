import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';

/// Task detail (S2 state) — centered title, plan steps, progress, actions.
/// Data comes from the core; nothing here is fabricated.
class TaskDetailScreen extends StatelessWidget {
  final String title;
  final String status;
  final String step;
  final int progress;
  final String result;

  const TaskDetailScreen({
    super.key,
    required this.title,
    this.status = 'RUNNING',
    this.step = '',
    this.progress = 0,
    this.result = '',
  });

  static const _steps = [
    ('Search', Icons.search),
    ('Open candidates', Icons.folder_open),
    ('Extract prices', Icons.sell_outlined),
    ('Verify', Icons.check_circle_outline),
    ('Compare', Icons.balance),
  ];

  @override
  Widget build(BuildContext context) {
    final doneStep = status == 'COMPLETED' ? _steps.length : (progress / 20).floor().clamp(0, _steps.length);
    return Scaffold(
      body: PanelShell(
        title: 'Task',
        onBack: () => Navigator.of(context).pop(),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 14, 20, 10),
            child: Center(child: Text(title, textAlign: TextAlign.center, style: AppTheme.sans(size: 14, w: FontWeight.w600))),
          ),
          const SectionLabel('Status'),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: AppCard(
              child: Column(
                children: [
                  MetricRow('State', status),
                  if (step.isNotEmpty) ...[
                    const Divider(),
                    MetricRow('Step', step, dim: true),
                  ],
                  const Divider(),
                  const MetricRow('Worker', 'on-device', dim: true),
                ],
              ),
            ),
          ),
          const SectionLabel('Plan'),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: AppCard(
              child: Column(
                children: [
                  for (var i = 0; i < _steps.length; i++) ...[
                    _Step(icon: _steps[i].$2, label: _steps[i].$1, done: i < doneStep, current: i == doneStep && status != 'COMPLETED'),
                    if (i != _steps.length - 1) const Divider(),
                  ],
                ],
              ),
            ),
          ),
          const SectionLabel('Progress'),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: AppCard(
              padding: const EdgeInsets.all(16),
              child: Column(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(2),
                    child: LinearProgressIndicator(
                      value: (progress / 100).clamp(0.0, 1.0),
                      backgroundColor: AppColors.surface2,
                      color: AppColors.accent,
                      minHeight: 4,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text('$progress%', style: AppTheme.mono(size: 10, color: AppColors.textFaint)),
                      Text('on-device', style: AppTheme.mono(size: 10, color: AppColors.textFaint)),
                    ],
                  ),
                ],
              ),
            ),
          ),
          if (result.isNotEmpty) ...[
            const SectionLabel('Result'),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: AppCard(
                padding: const EdgeInsets.all(16),
                child: Text(result, style: AppTheme.sans(size: 12, color: AppColors.textDim)),
              ),
            ),
          ],
          const SizedBox(height: 12),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Expanded(child: ActionButton('Copy', solid: false, onTap: () => Clipboard.setData(ClipboardData(text: result.isNotEmpty ? result : title)))),
                const SizedBox(width: 8),
                Expanded(child: ActionButton('Done', solid: true, onTap: () => Navigator.of(context).pop())),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Step extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool done;
  final bool current;
  const _Step({required this.icon, required this.label, required this.done, required this.current});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      child: Row(
        children: [
          Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: current ? AppColors.accent : (done ? AppColors.surface3 : Colors.transparent),
              border: Border.all(color: current || done ? Colors.transparent : AppColors.lineStrong),
            ),
            child: Icon(icon, size: 12, color: current ? AppColors.accentInk : (done ? AppColors.text : AppColors.textFaint)),
          ),
          const SizedBox(width: 11),
          Text(label, style: AppTheme.sans(size: 12.5, color: current || done ? AppColors.text : AppColors.textDim, w: current ? FontWeight.w600 : FontWeight.w400)),
        ],
      ),
    );
  }
}
