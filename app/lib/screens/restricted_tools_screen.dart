import 'package:flutter/material.dart';

import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/toast.dart';

/// Settings → Restricted tools.
///
/// Each row is a real tool with a working execute path. [active] is compiled
/// false, so tapping Run still executes and comes back refused. There is no
/// switch that can turn them on.
class RestrictedToolsScreen extends StatefulWidget {
  const RestrictedToolsScreen({super.key});

  @override
  State<RestrictedToolsScreen> createState() => _RestrictedToolsScreenState();
}

class _RestrictedToolsScreenState extends State<RestrictedToolsScreen> {
  List<Map<String, dynamic>> _tools = const [];
  String? _runningId;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final rows = await NativeBridge.guard(
      NativeBridge.listRestrictedTools,
      const <Map<String, dynamic>>[],
      'restricted tools unavailable',
    );
    if (!mounted) return;
    setState(() => _tools = rows);
  }

  Future<void> _run(Map<String, dynamic> tool) async {
    final id = tool['id'] as String? ?? '';
    if (id.isEmpty) return;
    setState(() => _runningId = id);
    final result = await NativeBridge.guard(
      () => NativeBridge.runRestrictedTool(id),
      const <String, dynamic>{
        'ran': true,
        'ok': false,
        'active': false,
        'reason': 'restricted tool is off (active=false)',
      },
      'restricted tool failed to run',
    );
    if (!mounted) return;
    setState(() => _runningId = null);
    final reason = result['reason'] as String? ?? 'off';
    AppToast.show(context, reason);
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
              title: 'Restricted tools',
              onBack: () => Navigator.of(context).pop(),
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.only(bottom: 28),
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                  child: Text(
                    'These tools have a working execute path. Active is set to '
                    'false, so they run and refuse. There is no switch that '
                    'turns them on.',
                    style: AppTheme.sans(size: 12, color: AppColors.textDim, height: 1.5),
                  ),
                ),
                const SectionLabel('Grade · off / safe / active'),
                AppCard(
                  child: Column(
                    children: withDividers([
                      if (_tools.isEmpty)
                        const EmptyNote('No restricted tools listed.')
                      else
                        for (final t in _tools) _row(t),
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

  Widget _row(Map<String, dynamic> tool) {
    final id = tool['id'] as String? ?? '';
    final title = tool['title'] as String? ?? id;
    final summary = tool['summary'] as String? ?? '';
    final grade = (tool['grade'] as String? ?? 'off').toLowerCase();
    final active = tool['active'] == true;
    final running = _runningId == id;
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(title, style: AppTheme.sans(size: 13, w: FontWeight.w600)),
              ),
              _gradeChip(grade),
              const SizedBox(width: 6),
              _activeChip(active),
            ],
          ),
          if (summary.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(summary,
                style: AppTheme.sans(size: 11.5, color: AppColors.textDim, height: 1.45)),
          ],
          const SizedBox(height: 10),
          Row(
            children: [
              Text(
                'active = ${active ? 'true' : 'false'}',
                style: AppTheme.mono(size: 10, color: AppColors.textFaint),
              ),
              const Spacer(),
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: running ? null : () => _run(tool),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  decoration: BoxDecoration(
                    color: AppColors.surface2,
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: AppColors.lineStrong),
                  ),
                  child: Text(
                    running ? 'RUNNING' : 'RUN',
                    style: AppTheme.mono(
                      size: 9.5,
                      w: FontWeight.w700,
                      color: AppColors.textDim,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _gradeChip(String grade) {
    final Color fg;
    switch (grade) {
      case 'active':
        fg = AppColors.success;
        break;
      case 'safe':
        fg = AppColors.text;
        break;
      default:
        fg = AppColors.textFaint;
    }
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: AppColors.surface2,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.lineStrong),
      ),
      child: Text(
        grade.toUpperCase(),
        style: AppTheme.mono(size: 9, w: FontWeight.w700, color: fg),
      ),
    );
  }

  Widget _activeChip(bool active) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: AppColors.surface2,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.lineStrong),
      ),
      child: Text(
        active ? 'ON' : 'OFF',
        style: AppTheme.mono(
          size: 9,
          w: FontWeight.w700,
          color: active ? AppColors.success : AppColors.textFaint,
        ),
      ),
    );
  }
}
