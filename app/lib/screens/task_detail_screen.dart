import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';

/// Task detail (S2 state) — centered title, plan steps with semantic icons,
/// progress, result with Copy / Run again.
class TaskDetailScreen extends StatelessWidget {
  final String title;
  const TaskDetailScreen({super.key, required this.title});

  static const _steps = [
    ('Search', Icons.search, true),
    ('Open candidates', Icons.folder_open, true),
    ('Extract prices', Icons.sell_outlined, false),
    ('Verify', Icons.check_circle_outline, false),
    ('Compare', Icons.balance, false),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: PanelShell(
        title: 'Task',
        onBack: () => Navigator.of(context).pop(),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 14, 20, 10),
            child: Center(child: Text(title, textAlign: TextAlign.center, style: AppTheme.sans(size: 14, w: FontWeight.w600))),
          ),
          const SectionLabel('Plan'),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: AppCard(
              child: Column(
                children: [
                  for (var i = 0; i < _steps.length; i++) ...[
                    _Step(icon: _steps[i].$2, label: _steps[i].$1, done: _steps[i].$3, current: i == 2),
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
                    child: const LinearProgressIndicator(
                      value: 0.58,
                      backgroundColor: AppColors.surface2,
                      color: AppColors.accent,
                      minHeight: 4,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text('58%', style: AppTheme.mono(size: 10, color: AppColors.textFaint)),
                      Text('on-device', style: AppTheme.mono(size: 10, color: AppColors.textFaint)),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SectionLabel('Result'),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: AppCard(
              padding: const EdgeInsets.all(16),
              child: Text(
                'Top results parsed from the web, held locally. Nothing leaves the device.',
                style: AppTheme.sans(size: 12, color: AppColors.textDim),
              ),
            ),
          ),
          const SizedBox(height: 12),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                ActionButton('Copy', solid: false, onTap: () {}),
                const SizedBox(width: 8),
                ActionButton('Run again', solid: true, onTap: () {}),
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
              color: current
                  ? AppColors.accent
                  : done
                      ? AppColors.surface3
                      : Colors.transparent,
              border: Border.all(color: current || done ? Colors.transparent : AppColors.lineStrong),
            ),
            child: Icon(
              icon,
              size: 12,
              color: current ? AppColors.accentInk : (done ? AppColors.text : AppColors.textFaint),
            ),
          ),
          const SizedBox(width: 11),
          Text(
            label,
            style: AppTheme.sans(
              size: 12.5,
              color: current || done ? AppColors.text : AppColors.textDim,
              w: current ? FontWeight.w600 : FontWeight.w400,
            ),
          ),
        ],
      ),
    );
  }
}
