import 'dart:async';

import 'package:flutter/material.dart';
import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/anchored_menu.dart';
import '../widgets/common.dart';
import '../widgets/toast.dart';
import 'home_screen.dart' show taskIcon;
import 'memory_screen.dart';

/// Tasks (S5) — every task the core knows about, newest first. Live rows show
/// the current step; finished rows show a muted DONE chip. Matches `#v-tasks`.
///
/// Rows can be deleted one at a time (long-press → menu) or all at once (the
/// header's delete-all icon). A Memory icon opens the on-device memory screen,
/// which used to live behind the developer panel.
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

  /// Delete one task by id and drop it from the list immediately.
  Future<void> _deleteTask(Map<String, dynamic> task) async {
    final id = task['id'] as int?;
    if (id == null) return;
    final ok = await NativeBridge.guard(
      () => NativeBridge.deleteTask(id),
      false,
      'could not delete task',
    );
    if (!mounted) return;
    if (!ok) {
      AppToast.show(context, 'Could not delete that task');
      return;
    }
    setState(() {
      _tasks = _tasks.where((t) => t['id'] != id).toList();
    });
    AppToast.show(context, 'Task deleted');
  }

  /// Delete all tasks after confirmation.
  Future<void> _deleteAll() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        backgroundColor: AppColors.overlay,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppColors.isWarm ? 22 : 14),
        ),
        title: Text(
          'Delete all tasks?',
          style: AppTheme.sans(
            size: AppColors.isWarm ? 16 : 15,
            color: AppColors.overlayInk,
            w: FontWeight.w700,
          ),
        ),
        content: Text(
          'This removes every task and anything they remember. It cannot be undone.',
          style: AppTheme.sans(
            size: 12,
            color: AppColors.overlayMuted,
            height: 1.5,
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: Text('Cancel',
                style: AppTheme.sans(
                    size: 12.5, color: AppColors.overlayMuted)),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text('Delete all',
                style: AppTheme.sans(
                    size: 12.5, color: AppColors.danger, w: FontWeight.w600)),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final ok = await NativeBridge.guard(
      NativeBridge.deleteAllTasks,
      false,
      'could not clear tasks',
    );
    if (!mounted) return;
    if (!ok) {
      AppToast.show(context, 'Could not clear tasks');
      return;
    }
    setState(() => _tasks = const []);
    AppToast.show(context, 'All tasks deleted');
  }

  void _openMemory() {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => const MemoryScreen()),
    );
  }

  @override
  Widget build(BuildContext context) => ScreenSurface(child: _buildBody(context));

  Widget _buildBody(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SafeArea(bottom: false, child: SizedBox(height: 8)),
        // Header: memory access + delete-all, right-aligned over the list.
        Padding(
          padding: const EdgeInsets.fromLTRB(18, 0, 18, 6),
          child: Row(
            children: [
              const Expanded(
                child: SectionLabel('All tasks'),
              ),
              _headerIcon(
                icon: Icons.memory_outlined,
                tooltip: 'Memory',
                onTap: _openMemory,
              ),
              const SizedBox(width: 8),
              if (_tasks.isNotEmpty)
                _headerIcon(
                  icon: Icons.delete_sweep_outlined,
                  tooltip: 'Delete all tasks',
                  onTap: _deleteAll,
                ),
            ],
          ),
        ),
        Expanded(
          child: ListView(
            controller: widget.scrollController,
            padding: const EdgeInsets.only(bottom: 120),
            children: [
              AppCard(
                child: !_loaded
                    ? Padding(
                        padding: const EdgeInsets.all(24),
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
                              for (final t in _tasks) Builder(
                                builder: (rowContext) => _row(rowContext, t),
                              ),
                            ]),
                          ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  /// A small, tappable header button (Memory, Delete all).
  Widget _headerIcon({
    required IconData icon,
    required String tooltip,
    required VoidCallback onTap,
  }) {
    return Tooltip(
      message: tooltip,
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: Container(
          width: 34,
          height: 34,
          decoration: BoxDecoration(
            color: AppColors.surface2,
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: AppColors.lineStrong),
          ),
          child: Icon(icon, size: 16, color: AppColors.textDim),
        ),
      ),
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

  Widget _row(BuildContext rowContext, Map<String, dynamic> task) {
    final status = task['status'] as String? ?? 'QUEUED';
    final running = _live.contains(status);
    final step = task['step'] as String? ?? '';
    final schedule = task['schedule'] as String? ?? 'NEVER';
    final watching = schedule != 'NEVER' && schedule.isNotEmpty;
    return ListRow(
      icon: taskIcon(task['instruction'] as String? ?? ''),
      title: task['instruction'] as String? ?? 'Task',
      subtitle: running && step.isNotEmpty
          ? 'on-device · $step'
          : watching
              ? 'watching · ${schedule.toLowerCase()}'
              : 'on-device',
      trailing: StatusChip(
        watching && !running ? 'WATCHING' : _chipLabel(status),
        tone: running || watching ? ChipTone.running : ChipTone.done,
      ),
      onTap: () => widget.onOpenTask(task),
      onLongPress: () => _taskMenu(rowContext, task),
    );
  }

  /// Long-press a task row for a small popover with the delete action.
  Future<void> _taskMenu(BuildContext rowContext, Map<String, dynamic> task) async {
    final action = await showAnchoredMenu<String>(
      context: rowContext,
      title: 'Task',
      options: const [
        MenuOption(id: 'open', label: 'Open', icon: Icons.open_in_new),
        MenuOption(id: 'delete', label: 'Delete task', icon: Icons.delete_outline),
      ],
    );
    if (!mounted || action == null) return;
    if (action == 'open') {
      widget.onOpenTask(task);
    } else if (action == 'delete') {
      await _deleteTask(task);
    }
  }
}
