import 'dart:async';

import 'package:flutter/material.dart';
import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'home_screen.dart' show taskIcon;

/// Tasks (S5) — every task the core knows about, newest first. Live rows show
/// the current step; finished rows show a muted DONE chip. Matches `#v-tasks`.
class TasksScreen extends StatefulWidget {
  final void Function(Map<String, dynamic> task) onOpenTask;
  final ScrollController? scrollController;

  /// Whether this destination is the one on screen. Destinations live in an
  /// IndexedStack, so without this they would keep polling in the background.
  final bool isActive;

  const TasksScreen({
    super.key,
    required this.onOpenTask,
    this.scrollController,
    this.isActive = true,
  });

  @override
  State<TasksScreen> createState() => _TasksScreenState();
}

class _TasksScreenState extends State<TasksScreen> {
  List<Map<String, dynamic>> _tasks = const [];
  bool _loaded = false;
  Timer? _poll;

  static const _live = {'RUNNING', 'QUEUED', 'WAITING', 'VERIFYING'};

  @override
  void initState() {
    super.initState();
    _load();
    _poll = Timer.periodic(const Duration(seconds: 3), (_) {
      if (widget.isActive) _load();
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    final tasks = await NativeBridge.guard(
      NativeBridge.recentTasks,
      const <Map<String, dynamic>>[],
      'tasks unavailable',
    );
    if (!mounted) return;
    setState(() {
      _tasks = tasks;
      _loaded = true;
    });
  }

  @override
  Widget build(BuildContext context) => ScreenSurface(child: _buildBody(context));

  Widget _buildBody(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SafeArea(bottom: false, child: SizedBox(height: 8)),
        Expanded(
          child: ListView(
            controller: widget.scrollController,
            padding: const EdgeInsets.only(bottom: 120),
            children: [
              const SectionLabel('All tasks'),
              AppCard(
                child: !_loaded
                    ? const Padding(
                        padding: EdgeInsets.all(24),
                        child: Center(
                          child: SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.accent),
                          ),
                        ),
                      )
                    : _tasks.isEmpty
                        ? const EmptyNote('No tasks yet — type "find …" in the bar.')
                        : Column(
                            children: withDividers([
                              for (final t in _tasks) _row(t),
                            ]),
                          ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  /// Wireframe chip vocabulary: a finished task reads DONE, not COMPLETED.
  static String _chipLabel(String status) {
    switch (status) {
      case 'COMPLETED':
        return 'DONE';
      case 'VERIFYING':
        return 'RUNNING';
      default:
        return status;
    }
  }

  Widget _row(Map<String, dynamic> task) {
    final status = task['status'] as String? ?? 'QUEUED';
    final running = _live.contains(status);
    final step = task['step'] as String? ?? '';
    return ListRow(
      icon: taskIcon(task['instruction'] as String? ?? ''),
      title: task['instruction'] as String? ?? 'Task',
      subtitle: running && step.isNotEmpty ? 'on-device · $step' : 'on-device',
      trailing: StatusChip(
        _chipLabel(status),
        tone: running ? ChipTone.running : ChipTone.done,
      ),
      onTap: () => widget.onOpenTask(task),
    );
  }
}
