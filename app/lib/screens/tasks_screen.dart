import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../bridge/native_bridge.dart';

/// Tasks (S5) — the real task list from the Java core via NativeBridge.
class TasksScreen extends StatefulWidget {
  final ValueChanged<String> onOpenTask;
  const TasksScreen({super.key, required this.onOpenTask});

  @override
  State<TasksScreen> createState() => _TasksScreenState();
}

class _TasksScreenState extends State<TasksScreen> {
  List<Map<String, dynamic>> _tasks = [];
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final tasks = await NativeBridge.recentTasks();
      if (!mounted) return;
      setState(() { _tasks = tasks; _loaded = true; });
    } catch (_) {
      if (mounted) setState(() => _loaded = true);
    }
  }

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
          child: !_loaded
              ? const Center(child: CircularProgressIndicator(color: AppColors.accent))
              : _tasks.isEmpty
                  ? const Center(child: Text('No tasks yet', style: TextStyle(color: AppColors.textFaint)))
                  : ListView(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      children: [
                        AppCard(
                          child: Column(
                            children: [
                              for (var i = 0; i < _tasks.length; i++) ...[
                                _TaskRow(
                                  task: _tasks[i],
                                  onTap: () => widget.onOpenTask(_tasks[i]['instruction'] as String),
                                ),
                                if (i != _tasks.length - 1) const Divider(),
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
  final Map<String, dynamic> task;
  final VoidCallback onTap;
  const _TaskRow({required this.task, required this.onTap});

  static const _icons = {
    'COMPLETED': Icons.check_circle_outline,
    'FAILED': Icons.error_outline,
    'WAITING': Icons.pause_circle_outline,
    'QUEUED': Icons.schedule,
  };

  @override
  Widget build(BuildContext context) {
    final status = task['status'] as String? ?? 'RUNNING';
    final done = status == 'COMPLETED';
    final icon = _icons[status] ?? Icons.auto_awesome;
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
                  Text(task['instruction'] as String, style: AppTheme.sans(size: 13, w: FontWeight.w600), maxLines: 1, overflow: TextOverflow.ellipsis),
                  if ((task['step'] as String? ?? '').isNotEmpty)
                    Text(task['step'] as String, style: AppTheme.mono(size: 10, color: AppColors.textFaint)),
                ],
              ),
            ),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
              decoration: BoxDecoration(
                color: done ? AppColors.surface3 : AppColors.accent,
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text(status, style: AppTheme.mono(size: 9, color: done ? AppColors.textDim : AppColors.accentInk, w: FontWeight.w600)),
            ),
          ],
        ),
      ),
    );
  }
}
