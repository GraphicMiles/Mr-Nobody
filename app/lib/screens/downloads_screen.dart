import 'package:flutter/material.dart';
import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/debug_fab.dart';
import '../widgets/toast.dart';

/// Downloads (S8) — storage summary plus the real DownloadManager rows
/// (running / done / failed), matching `#v-downloads`.
class DownloadsScreen extends StatefulWidget {
  const DownloadsScreen({super.key});

  @override
  State<DownloadsScreen> createState() => _DownloadsScreenState();
}

class _DownloadsScreenState extends State<DownloadsScreen> {
  // android.app.DownloadManager status constants.
  static const _statusRunning = 2;
  static const _statusSuccessful = 8;
  static const _statusFailed = 16;

  List<Map<String, dynamic>> _items = const [];
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final items = await NativeBridge.guard(
      NativeBridge.downloads,
      const <Map<String, dynamic>>[],
      'downloads unavailable',
    );
    if (!mounted) return;
    setState(() {
      _items = items;
      _loaded = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    var done = 0;
    var bytes = 0;
    for (final d in _items) {
      if (d['status'] == _statusSuccessful) done++;
      bytes += ((d['size'] as num?) ?? 0).toInt();
    }

    return Scaffold(
      backgroundColor: AppColors.bg,
      body: PanelShell(
        title: 'Downloads',
        onBack: () => Navigator.of(context).pop(),
        overlay: const DebugOverlay(bottomInset: 20),
        children: [
          const SectionLabel('Storage'),
          AppCard(
            child: Column(
              children: withDividers([
                MetricRow('Files downloaded', '$done'),
                MetricRow('Storage used', humanBytes(bytes)),
              ]),
            ),
          ),
          const SectionLabel('Recent'),
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
                : _items.isEmpty
                    ? const EmptyNote('No downloads yet')
                    : Column(children: withDividers([for (final d in _items) _row(d)])),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
            child: Text(
              'Files open with Android’s own file handling.',
              style: AppTheme.mono(size: 10.5, color: AppColors.textMuted, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }

  Widget _row(Map<String, dynamic> d) {
    final name = d['name'] as String? ?? 'download';
    final size = ((d['size'] as num?) ?? 0).toInt();
    final status = (d['status'] as num?)?.toInt() ?? 0;
    final downloaded = ((d['downloaded'] as num?) ?? 0).toInt();
    final running = status == _statusRunning;
    final failed = status == _statusFailed;
    final pct = (running && size > 0) ? (downloaded / size).clamp(0.0, 1.0) : 0.0;

    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () {
        if (running) {
          AppToast.show(context, 'Still downloading…');
        } else if (failed) {
          AppToast.show(context, 'Download failed');
        } else {
          AppToast.show(context, 'Opening $name…');
        }
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
        child: Row(
          children: [
            Container(
              width: 34,
              height: 34,
              decoration: BoxDecoration(
                color: AppColors.surface2,
                borderRadius: BorderRadius.circular(9),
                border: Border.all(color: AppColors.line),
              ),
              child: Icon(_iconFor(name), size: 14, color: AppColors.textDim),
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.baseline,
                    textBaseline: TextBaseline.alphabetic,
                    children: [
                      Expanded(
                        child: Text(
                          name,
                          style: AppTheme.sans(size: 12.5, w: FontWeight.w600),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      const SizedBox(width: 10),
                      Text(
                        size > 0 ? humanBytes(size) : '—',
                        style: AppTheme.mono(size: 10, color: AppColors.textMuted),
                      ),
                    ],
                  ),
                  const SizedBox(height: 7),
                  if (running)
                    Row(
                      children: [
                        Expanded(child: ProgressBar(pct)),
                        const SizedBox(width: 10),
                        Text('${(pct * 100).round()}%',
                            style: AppTheme.mono(size: 10, color: AppColors.textMuted)),
                      ],
                    )
                  else
                    Text(
                      failed ? 'failed' : 'tap to open',
                      style: AppTheme.mono(size: 10, color: AppColors.textMuted),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 10),
            SizedBox(
              width: 28,
              height: 28,
              child: running
                  ? const Padding(
                      padding: EdgeInsets.all(7),
                      child: CircularProgressIndicator(strokeWidth: 2, color: AppColors.accent),
                    )
                  : Icon(
                      failed ? Icons.refresh : Icons.check,
                      size: 14,
                      color: failed ? AppColors.textDim : AppColors.text,
                    ),
            ),
          ],
        ),
      ),
    );
  }

  static IconData _iconFor(String name) {
    final n = name.toLowerCase();
    if (n.endsWith('.pdf')) return Icons.picture_as_pdf_outlined;
    if (n.endsWith('.jpg') || n.endsWith('.jpeg') || n.endsWith('.png') || n.endsWith('.webp')) {
      return Icons.image_outlined;
    }
    if (n.endsWith('.zip') || n.endsWith('.tar') || n.endsWith('.gz')) return Icons.folder_zip_outlined;
    return Icons.insert_drive_file_outlined;
  }
}

/// Human-readable byte count for the storage rows.
String humanBytes(int bytes) {
  if (bytes <= 0) return '0 B';
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).round()} KB';
  if (bytes < 1024 * 1024 * 1024) return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
}
