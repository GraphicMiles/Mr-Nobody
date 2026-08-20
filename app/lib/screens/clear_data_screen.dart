import 'package:flutter/material.dart';
import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/debug_fab.dart';
import '../widgets/toast.dart';

/// Clear data (S7) — tick the buckets, then Cancel / Clear. The three
/// privacy-critical buckets are pre-ticked, exactly as in `#v-clear`.
///
/// Clearing calls straight into the core; nothing is faked.
class ClearDataScreen extends StatefulWidget {
  final VoidCallback? onBrowserDataCleared;

  const ClearDataScreen({super.key, this.onBrowserDataCleared});

  @override
  State<ClearDataScreen> createState() => _ClearDataScreenState();
}

class _ClearDataScreenState extends State<ClearDataScreen> {
  static const _buckets = [
    ('history', 'History', true),
    ('cookies', 'Cookies', true),
    ('cache', 'Cache', true),
    ('sitedata', 'Site data', false),
    ('taskstate', 'Task state', false),
    ('workspace', 'Download workspace', false),
  ];

  late final Map<String, bool> _checked = {
    for (final b in _buckets) b.$1: b.$3,
  };
  bool _busy = false;

  Future<void> _clear() async {
    final selected = _checked.entries.where((e) => e.value).map((e) => e.key).toList();
    if (selected.isEmpty) {
      AppToast.show(context, 'Nothing selected');
      return;
    }
    setState(() => _busy = true);
    await NativeBridge.guard(
      () => NativeBridge.clearData(selected),
      const <String, dynamic>{},
      'clear data failed',
    );
    final clearedBrowserState = selected.contains('cookies')
        || selected.contains('cache')
        || selected.contains('sitedata');
    if (clearedBrowserState) widget.onBrowserDataCleared?.call();
    if (!mounted) return;
    setState(() => _busy = false);
    AppToast.show(context, 'Data cleared');
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      body: PanelShell(
        title: 'Clear data',
        onBack: () => Navigator.of(context).pop(),
        overlay: const DebugOverlay(bottomInset: 20),
        children: [
          const SectionLabel('Clear browsing data'),
          AppCard(
            child: Column(
              children: withDividers([
                for (final b in _buckets) _row(b.$1, b.$2),
              ]),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Expanded(child: ActionButton('Cancel', onTap: () => Navigator.of(context).pop())),
                const SizedBox(width: 8),
                Expanded(
                  child: ActionButton(
                    _busy ? 'Clearing…' : 'Clear data',
                    solid: true,
                    onTap: _busy ? () {} : _clear,
                  ),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 14),
            child: Text(
              'Everything listed here lives only on this device, so clearing it '
              'deletes it for good — there is no copy anywhere else.',
              style: AppTheme.sans(size: 11, color: AppColors.textMuted, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }

  Widget _row(String id, String label) {
    final value = _checked[id] ?? false;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => setState(() => _checked[id] = !value),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        child: Row(
          children: [
            SquareCheck(value),
            const SizedBox(width: 10),
            Text(label, style: AppTheme.sans(size: 13)),
          ],
        ),
      ),
    );
  }
}
