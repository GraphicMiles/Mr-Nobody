import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../bridge/native_bridge.dart';

/// Downloads (S8) — Storage summary + Recent list from the system DownloadManager
/// (via the Java core). No hardcoded values.
class DownloadsScreen extends StatefulWidget {
  const DownloadsScreen({super.key});

  @override
  State<DownloadsScreen> createState() => _DownloadsScreenState();
}

class _DownloadsScreenState extends State<DownloadsScreen> {
  List<Map<String, dynamic>> _items = [];
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final items = await NativeBridge.downloads();
      if (!mounted) return;
      setState(() { _items = items; _loaded = true; });
    } catch (_) {
      if (mounted) setState(() => _loaded = true);
    }
  }

  @override
  Widget build(BuildContext context) {
    var done = 0;
    var bytes = 0;
    for (final d in _items) {
      if (d['status'] == 8) done++; // DownloadManager.STATUS_SUCCESSFUL
      bytes += (d['size'] as num?)?.toInt() ?? 0;
    }
    return Scaffold(
      body: PanelShell(
        title: 'Downloads',
        onBack: () => Navigator.of(context).pop(),
        children: [
          const SectionLabel('Storage'),
          _Card([
            MetricRow('Files downloaded', '$done'),
            const Divider(),
            MetricRow('Storage used', _human(bytes)),
          ]),
          const SectionLabel('Recent'),
          !_loaded
              ? const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator(color: AppColors.accent)))
              : _items.isEmpty
                  ? const Padding(padding: EdgeInsets.all(24), child: Center(child: Text('No downloads yet', style: TextStyle(color: AppColors.textFaint))))
                  : _Card(List.generate(_items.length, (i) {
                      final d = _items[i];
                      return Column(children: [
                        _DownloadRow(name: d['name'] as String? ?? 'download', size: _human((d['size'] as num?)?.toInt() ?? 0), status: d['status'] as int? ?? 0),
                        if (i != _items.length - 1) const Divider(),
                      ]);
                    })),
        ],
      ),
    );
  }

  static String _human(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).round()} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}

class _DownloadRow extends StatelessWidget {
  final String name;
  final String size;
  final int status;
  const _DownloadRow({required this.name, required this.size, required this.status});

  @override
  Widget build(BuildContext context) {
    final IconData stateIcon;
    switch (status) {
      case 8: stateIcon = Icons.check; break; // successful
      case 16: stateIcon = Icons.refresh; break; // failed
      default: stateIcon = Icons.download; break; // pending/running
    }
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      child: Row(
        children: [
          Container(
            width: 34,
            height: 34,
            decoration: BoxDecoration(color: AppColors.surface2, borderRadius: BorderRadius.circular(9), border: Border.all(color: AppColors.line)),
            child: const Icon(Icons.insert_drive_file_outlined, size: 15, color: AppColors.textDim),
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name, style: AppTheme.sans(size: 12.5, w: FontWeight.w600), maxLines: 1, overflow: TextOverflow.ellipsis),
                Text(size, style: AppTheme.mono(size: 10, color: AppColors.textFaint)),
              ],
            ),
          ),
          Icon(stateIcon, size: 14, color: status == 16 ? AppColors.textFaint : AppColors.textDim),
        ],
      ),
    );
  }
}

class _Card extends StatelessWidget {
  final List<Widget> children;
  const _Card(this.children);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: AppCard(child: Column(children: children)),
    );
  }
}
