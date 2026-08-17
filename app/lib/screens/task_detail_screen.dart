import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/debug_fab.dart';
import '../widgets/toast.dart';

/// Task detail — the agent's plan, where it is now, and what it produced.
/// Matches `#v-taskdetail`: centred title, plan steps, progress, actions.
///
/// Everything shown comes from the core's task record; nothing is invented.
class TaskDetailScreen extends StatefulWidget {
  final int? taskId;
  final String title;
  final String initialStatus;
  final String initialStep;
  final int initialProgress;

  const TaskDetailScreen({
    super.key,
    required this.title,
    this.taskId,
    this.initialStatus = 'QUEUED',
    this.initialStep = '',
    this.initialProgress = 0,
  });

  @override
  State<TaskDetailScreen> createState() => _TaskDetailScreenState();
}

class _TaskDetailScreenState extends State<TaskDetailScreen> {
  static const _plan = [
    ('Search', Icons.search),
    ('Open candidates', Icons.folder_open),
    ('Extract prices', Icons.sell_outlined),
    ('Verify', Icons.check_circle_outline),
    ('Compare', Icons.balance),
  ];

  late String _status = widget.initialStatus;
  late String _step = widget.initialStep;
  late int _progress = widget.initialProgress;
  String _result = '';
  String _error = '';
  String _worker = 'on-device';
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    if (widget.taskId != null) {
      _refresh();
      _poll = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
    }
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final id = widget.taskId;
    if (id == null) return;
    final task = await NativeBridge.guard(
      () => NativeBridge.task(id),
      null,
      'task $id unavailable',
    );
    if (!mounted || task == null) return;
    setState(() {
      _status = task['status'] as String? ?? _status;
      _step = task['step'] as String? ?? '';
      _progress = ((task['progress'] as num?) ?? _progress).toInt();
      _result = task['result'] as String? ?? '';
      _error = task['error'] as String? ?? '';
      _worker = (task['worker'] as String? ?? 'local') == 'local' ? 'on-device' : 'remote';
    });
    if (!_live) _poll?.cancel();
  }

  bool get _done => _status == 'COMPLETED';
  /// Statuses that mean a worker is (or will be) on this task.
  static const _liveStatuses = {'RUNNING', 'QUEUED', 'WAITING', 'VERIFYING'};

  bool get _live => _liveStatuses.contains(_status);

  Future<void> _cancel() async {
    final id = widget.taskId;
    if (id == null) return;
    final accepted = await NativeBridge.guard(
      () => NativeBridge.cancelTask(id),
      false,
      'could not cancel task',
    );
    if (!mounted) return;
    AppToast.show(context, accepted ? 'Stopping…' : 'Could not stop this task');
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final pct = _done ? 100 : _progress;
    final stopped = _status == 'CANCELLED' || _status == 'FAILED';
    final doneSteps = _done ? _plan.length : (pct / 20).floor().clamp(0, _plan.length);

    return Scaffold(
      backgroundColor: AppColors.bg,
      body: PanelShell(
        title: 'Task',
        onBack: () => Navigator.of(context).pop(),
        overlay: const IgnorePointer(
          ignoring: false,
          child: DebugOverlay(bottomInset: 20),
        ),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
            child: Center(
              child: Text(
                widget.title,
                textAlign: TextAlign.center,
                style: AppTheme.sans(size: 14, w: FontWeight.w600),
              ),
            ),
          ),
          AppCard(
            child: Column(
              children: [
                for (var i = 0; i < _plan.length; i++)
                  _PlanStep(
                    label: _plan[i].$1,
                    icon: _plan[i].$2,
                    done: i < doneSteps,
                    current: i == doneSteps && !_done && !stopped,
                  ),
              ],
            ),
          ),
          const SectionLabel('Progress'),
          AppCard(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                ProgressBar(pct / 100),
                const SizedBox(height: 7),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text('$pct%', style: AppTheme.mono(size: 10, color: AppColors.textMuted)),
                    Text(
                      _step.isEmpty ? _worker : '$_step · $_worker',
                      style: AppTheme.mono(size: 10, color: AppColors.textMuted),
                    ),
                  ],
                ),
              ],
            ),
          ),
          if (_error.isNotEmpty) ...[
            const SectionLabel('Error'),
            AppCard(
              padding: const EdgeInsets.all(14),
              child: Text(_error, style: AppTheme.sans(size: 12, color: AppColors.textDim, height: 1.5)),
            ),
          ],
          if (_result.isNotEmpty) ...[
            const SectionLabel('Result'),
            AppCard(
              padding: const EdgeInsets.all(14),
              child: SelectableText(
                _result,
                style: AppTheme.sans(size: 12.5, color: AppColors.textDim, height: 1.55),
              ),
            ),
          ],
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                if (_live)
                  Expanded(child: ActionButton('Stop task', onTap: _cancel)),
                if (_live) const SizedBox(width: 8),
                if (!_live)
                  Expanded(
                    child: ActionButton('Run again', onTap: _runAgain),
                  ),
                if (!_live) const SizedBox(width: 8),
                Expanded(
                  child: ActionButton(
                    'Copy result',
                    solid: true,
                    onTap: () {
                      if (_result.isEmpty) {
                        AppToast.show(context, 'Nothing to copy yet');
                        return;
                      }
                      Clipboard.setData(ClipboardData(text: _result));
                      AppToast.show(context, 'Result copied');
                    },
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _runAgain() async {
    await NativeBridge.guard(
      () => NativeBridge.runTask(widget.title),
      const <String, dynamic>{},
      'could not restart task',
    );
    if (!mounted) return;
    AppToast.show(context, 'Task queued again');
    Navigator.of(context).pop();
  }
}

/// `.plan-step` — circular mark (outline → filled when done → accent when
/// current) with the step name.
class _PlanStep extends StatelessWidget {
  final String label;
  final IconData icon;
  final bool done;
  final bool current;

  const _PlanStep({required this.label, required this.icon, required this.done, required this.current});

  @override
  Widget build(BuildContext context) {
    final Color markBg;
    final Color markFg;
    if (current) {
      markBg = AppColors.accent;
      markFg = AppColors.accentInk;
    } else if (done) {
      markBg = AppColors.surface3;
      markFg = AppColors.text;
    } else {
      markBg = Colors.transparent;
      markFg = AppColors.textMuted;
    }

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      child: Row(
        children: [
          Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
              color: markBg,
              shape: BoxShape.circle,
              border: Border.all(color: current ? AppColors.accent : AppColors.lineStrong),
            ),
            child: Icon(done && !current ? Icons.check : icon, size: 12, color: markFg),
          ),
          const SizedBox(width: 11),
          Text(
            label,
            style: AppTheme.sans(
              size: 12.5,
              color: (done || current) ? AppColors.text : AppColors.textDim,
              w: current ? FontWeight.w600 : FontWeight.w400,
            ),
          ),
        ],
      ),
    );
  }
}
