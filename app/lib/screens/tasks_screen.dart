import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'task_detail_screen.dart';

/// Tasks (S5) — all tasks with status chips. Tapping opens Task detail.
class TasksScreen extends StatelessWidget {
  const TasksScreen({super.key});

  static const _tasks = [
    ('Find laptop under ₦500,000', Icons.laptop_mac, 'RUNNING', 'on-device · searching'),
    ('Download report.pdf', Icons.download, 'WAITING', 'needs your approval'),
    ('Price watch · daily 08:00', Icons.trending_up, 'SCHEDULED', 'remote · scheduled'),
    ('Compare phones', Icons.balance, 'DONE', ''),
    ('Scrape reviews', Icons.star_outline, 'FAILED', ''),
  ];

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Container(
          color: AppColors.surface,
          padding: EdgeInsets.only(left: 8, top: 8 + MediaQuery.of(context).padding.top, right: 12, bottom: 8),
          child: Row(
            children: [
              IconButton(onPressed: () {}, icon: const Icon(Icons.chevron_left, color: AppColors.textDim, size: 26)),
              Text('Tasks', style: AppTheme.sans(size: 16, w: FontWeight.w700)),
            ],
          ),
        ),
        const SectionLabel('All tasks'),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            children: [
              AppCard(
                child: Column(
                  children: [
                    for (final (title, icon, status, sub) in _tasks) ...[
                      _TaskRow(
                        icon: icon,
                        title: title,
                        sub: sub,
                        status: status,
                        onTap: () => Navigator.of(context).push(
                          MaterialPageRoute(builder: (_) => TaskDetailScreen(title: title)),
                        ),
                      ),
                      if (title != _tasks.last.$1) const Divider(),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _TaskRow extends StatelessWidget {
  final IconData icon;
  final String title;
  final String sub;
  final String status;
  final VoidCallback onTap;
  const _TaskRow({required this.icon, required this.title, required this.sub, required this.status, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final done = status == 'DONE';
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Row(
          children: [
            Container(
              width: 28,
              height: 28,
              decoration: BoxDecoration(color: AppColors.surface2, borderRadius: BorderRadius.circular(9)),
              child: Icon(icon, size: 15, color: AppColors.textDim),
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: AppTheme.sans(size: 13, w: FontWeight.w600), maxLines: 1, overflow: TextOverflow.ellipsis),
                  if (sub.isNotEmpty) Text(sub, style: AppTheme.mono(size: 10, color: AppColors.textFaint)),
                ],
              ),
            ),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
              decoration: BoxDecoration(
                color: done ? AppColors.surface3 : AppColors.accent,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text(
                status,
                style: AppTheme.mono(size: 9, color: done ? AppColors.textDim : AppColors.accentInk, w: FontWeight.w600),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
