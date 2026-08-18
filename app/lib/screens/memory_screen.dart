import 'package:flutter/material.dart';

import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/toast.dart';

/// The agent's memory — what it remembers, plainly visible.
///
/// Everything here is on-device: the task history the agent can recall when
/// asked "what did I do about this before". It is shown, not hidden, because a
/// memory the user cannot see or erase is a profile, not a memory. A "forget
/// everything" button wipes it; nothing here ever leaves the phone.
class MemoryScreen extends StatefulWidget {
  const MemoryScreen({super.key});

  @override
  State<MemoryScreen> createState() => _MemoryScreenState();
}

class _MemoryScreenState extends State<MemoryScreen> {
  List<Map<String, dynamic>> _tasks = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final info = await NativeBridge.guard(
      NativeBridge.memoryInfo,
      const <String, dynamic>{},
      'memory unavailable',
    );
    if (!mounted) return;
    setState(() {
      // Each task is a raw Map<Object?, Object?> straight off the MethodChannel;
      // a lazy .cast<Map<String, dynamic>>() throws on first read because that
      // map type is not a subtype of Map<String, dynamic>. Copy each one.
      _tasks = ((info['tasks'] as List?) ?? const [])
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();
      _loading = false;
    });
  }

  Future<void> _forget() async {
    await NativeBridge.guard(
      NativeBridge.forgetMemory,
      null,
      'could not clear memory',
    );
    if (!mounted) return;
    setState(() => _tasks = const []);
    AppToast.show(context, 'Memory cleared');
  }

  @override
  Widget build(BuildContext context) {
    return ScreenSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SafeArea(
            bottom: false,
            child: TopBar(
              title: 'Memory',
              onBack: () => Navigator.of(context).pop(),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 10),
            child: Text(
              'What Mr Nobody remembers on this device. It never leaves the '
              'phone, and you can erase it at any time.',
              style: AppTheme.sans(size: 12, color: AppColors.textDim, height: 1.5),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Text(
                  '${_tasks.length} ${_tasks.length == 1 ? "task" : "tasks"} remembered',
                  style: AppTheme.mono(size: 10.5, color: AppColors.textMuted),
                ),
                const Spacer(),
                if (_tasks.isNotEmpty)
                  GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: _forget,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                      decoration: BoxDecoration(
                        color: AppColors.surface2,
                        borderRadius: BorderRadius.circular(999),
                        border: Border.all(color: AppColors.lineStrong),
                      ),
                      child: Text('FORGET EVERYTHING',
                          style: AppTheme.mono(
                              size: 8.5,
                              w: FontWeight.w700,
                              color: const Color(0xFFE5484D))),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 10),
          Expanded(
            child: _loading
                ? const Center(
                    child: CircularProgressIndicator(
                        strokeWidth: 1.5, color: AppColors.accent))
                : ListView(
                    padding: const EdgeInsets.only(bottom: 28),
                    children: [
                      if (_tasks.isEmpty)
                        Padding(
                          padding: const EdgeInsets.all(16),
                          child: Text('Nothing remembered yet.',
                              style: AppTheme.sans(
                                  size: 12.5, color: AppColors.textFaint)),
                        )
                      else
                        AppCard(
                          child: Column(
                            children: withDividers([
                              for (final t in _tasks) _row(t),
                            ]),
                          ),
                        ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }

  Widget _row(Map<String, dynamic> t) {
    final status = t['status'] as String? ?? '';
    final done = status == 'COMPLETED';
    final result = (t['result'] as String?) ?? '';
    final color = done
        ? const Color(0xFF3DDC84)
        : status == 'FAILED'
            ? const Color(0xFFE5484D)
            : AppColors.textMuted;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(done ? Icons.check_circle : Icons.circle_outlined,
              size: 14, color: color),
          const SizedBox(width: 9),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(t['instruction'] as String? ?? '',
                    style: AppTheme.sans(size: 13, w: FontWeight.w600)),
                if (result.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(
                    result.replaceAll(RegExp(r'\s+'), ' ').trim(),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: AppTheme.mono(
                        size: 10, color: AppColors.textFaint, height: 1.5),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}
