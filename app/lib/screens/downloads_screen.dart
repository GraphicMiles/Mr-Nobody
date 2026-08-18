import 'dart:async';

import 'package:flutter/material.dart';
import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/debug_fab.dart';
import '../widgets/menu_sheet.dart';
import '../widgets/toast.dart';

/// Downloads (S8) — what is arriving, how fast, over what connection, and what
/// you can do about it.
///
/// A download is handed to Android's DownloadManager, which keeps going on its
/// own; this screen is the only place inside the app where one can be
/// inspected, opened, or stopped.
class DownloadsScreen extends StatefulWidget {
  const DownloadsScreen({super.key});

  @override
  State<DownloadsScreen> createState() => _DownloadsScreenState();
}

class _DownloadsScreenState extends State<DownloadsScreen> {
  // android.app.DownloadManager status constants.
  static const _statusPending = 1;
  static const _statusRunning = 2;
  static const _statusPaused = 4;
  static const _statusSuccessful = 8;
  static const _statusFailed = 16;

  List<Map<String, dynamic>> _items = const [];
  Map<String, dynamic> _network = const {};
  bool _loaded = false;
  Timer? _poll;

  /// id → (bytes, at) from the previous sample, for the speed readout.
  final Map<int, (int, DateTime)> _lastSample = {};
  final Map<int, double> _speed = {};

  @override
  void initState() {
    super.initState();
    _load();
    // One second is short enough to feel live and long enough to give a stable
    // speed figure.
    _poll = Timer.periodic(const Duration(seconds: 1), (_) => _load());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    final items = await NativeBridge.guard(
      NativeBridge.downloads,
      const <Map<String, dynamic>>[],
      'downloads unavailable',
    );
    final network = await NativeBridge.guard(
      NativeBridge.networkStatus,
      const <String, dynamic>{},
      'network status unavailable',
    );
    if (!mounted) return;
    final now = DateTime.now();
    for (final d in items) {
      final id = _int(d['id']);
      final soFar = _int(d['downloaded']);
      final previous = _lastSample[id];
      if (previous != null) {
        final seconds = now.difference(previous.$2).inMilliseconds / 1000.0;
        if (seconds > 0.3) {
          final delta = soFar - previous.$1;
          // Smooth it a little: raw per-second deltas jump around.
          final instant = delta <= 0 ? 0.0 : delta / seconds;
          final prior = _speed[id] ?? instant;
          _speed[id] = prior * 0.6 + instant * 0.4;
          _lastSample[id] = (soFar, now);
        }
      } else {
        _lastSample[id] = (soFar, now);
      }
    }
    setState(() {
      _items = items;
      _network = network;
      _loaded = true;
    });
  }

  static int _int(Object? v) => (v as num?)?.toInt() ?? 0;

  @override
  Widget build(BuildContext context) {
    var done = 0;
    var bytes = 0;
    var active = 0;
    for (final d in _items) {
      final status = _int(d['status']);
      if (status == _statusSuccessful) {
        done++;
        bytes += _int(d['size']);
      }
      if (status == _statusRunning || status == _statusPending || status == _statusPaused) {
        active++;
      }
    }

    return Scaffold(
      backgroundColor: AppColors.bg,
      body: PanelShell(
        title: 'Downloads',
        onBack: () => Navigator.of(context).pop(),
        overlay: const DebugOverlay(bottomInset: 20),
        children: [
          const SectionLabel('Connection'),
          AppCard(
            child: Column(
              children: withDividers([
                MetricRow('Network', _networkLabel()),
                MetricRow('Estimated speed', _linkSpeedLabel()),
                if (active > 0) MetricRow('Downloading now', '$active'),
              ]),
            ),
          ),
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
              'Downloads are handled by Android itself, so they continue when Mr Nobody '
              'is closed. Stopping one here cancels it and deletes the partial file.',
              style: AppTheme.mono(size: 10.5, color: AppColors.textMuted, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }

  String _networkLabel() {
    final transport = _network['transport'] as String? ?? 'none';
    final online = _network['online'] as bool? ?? false;
    final metered = _network['metered'] as bool? ?? false;
    if (transport == 'none' || !online) return 'Offline';
    final name = switch (transport) {
      'wifi' => 'Wi-Fi',
      'cellular' => 'Mobile data',
      'ethernet' => 'Ethernet',
      'vpn' => 'VPN',
      _ => 'Connected',
    };
    return metered ? '$name · metered' : name;
  }

  String _linkSpeedLabel() {
    final kbps = _int(_network['downKbps']);
    if (kbps <= 0) return '—';
    if (kbps < 1000) return '$kbps kbps';
    return '${(kbps / 1000).toStringAsFixed(1)} Mbps';
  }

  Widget _row(Map<String, dynamic> d) {
    final id = _int(d['id']);
    final name = d['name'] as String? ?? 'download';
    final size = _int(d['size']);
    final downloaded = _int(d['downloaded']);
    final status = _int(d['status']);
    final running = status == _statusRunning || status == _statusPending;
    final paused = status == _statusPaused;
    final failed = status == _statusFailed;
    final pct = (size > 0) ? (downloaded / size).clamp(0.0, 1.0) : 0.0;
    final speed = _speed[id] ?? 0;

    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => _openOrExplain(id, status, name),
      onLongPress: () => _details(d),
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
              child: Icon(_iconFor(name, d['mime'] as String?), size: 14, color: AppColors.textDim),
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
                        _typeLabel(name, d['mime'] as String?),
                        style: AppTheme.mono(size: 9, color: AppColors.textMuted),
                      ),
                    ],
                  ),
                  const SizedBox(height: 7),
                  if (running || paused)
                    Row(
                      children: [
                        Expanded(child: ProgressBar(pct)),
                        const SizedBox(width: 10),
                        Text(
                          paused
                              ? 'paused'
                              : '${humanBytes(downloaded)} / ${size > 0 ? humanBytes(size) : '—'}'
                                  '${speed > 0 ? ' · ${humanBytes(speed.round())}/s' : ''}',
                          style: AppTheme.mono(size: 9.5, color: AppColors.textMuted),
                        ),
                      ],
                    )
                  else
                    Text(
                      failed ? 'failed — tap for details' : '${humanBytes(size)} · tap to open',
                      style: AppTheme.mono(size: 10, color: AppColors.textMuted),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 6),
            GestureDetector(
              onTap: () => _details(d),
              behavior: HitTestBehavior.opaque,
              child: const Padding(
                padding: EdgeInsets.all(6),
                child: Icon(Icons.more_vert, size: 16, color: AppColors.textFaint),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _openOrExplain(int id, int status, String name) async {
    if (status == _statusSuccessful) {
      final opened = await NativeBridge.guard(
        () => NativeBridge.openDownload(id),
        false,
        'could not open download',
      );
      if (!mounted) return;
      AppToast.show(context, opened ? 'Opening $name…' : 'No app can open this file');
      return;
    }
    if (status == _statusFailed) {
      AppToast.show(context, 'This download failed');
      return;
    }
    AppToast.show(context, 'Still downloading…');
  }

  void _details(Map<String, dynamic> d) {
    final id = _int(d['id']);
    final name = d['name'] as String? ?? 'download';
    final status = _int(d['status']);
    final finished = status == _statusSuccessful;

    showMenuSheet(context, [
      if (finished)
        SheetItem(Icons.open_in_new, 'Open', () => _openOrExplain(id, status, name)),
      SheetItem(Icons.info_outline, 'Details', () => _showDetails(d)),
      SheetItem(
        finished ? Icons.delete_outline : Icons.stop_circle_outlined,
        finished ? 'Delete file' : 'Stop and delete',
        () async {
          final removed = await NativeBridge.guard(
            () => NativeBridge.removeDownload(id),
            false,
            'could not remove download',
          );
          if (!mounted) return;
          AppToast.show(context, removed ? 'Removed $name' : 'Could not remove it');
          _load();
        },
      ),
    ]);
  }

  void _showDetails(Map<String, dynamic> d) {
    final size = _int(d['size']);
    final downloaded = _int(d['downloaded']);
    showDialog<void>(
      context: context,
      builder: (c) => AlertDialog(
        backgroundColor: AppColors.surface,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(d['name'] as String? ?? 'download',
            style: AppTheme.sans(size: 14, w: FontWeight.w700)),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              _detail('Type', _typeLabel(d['name'] as String? ?? '', d['mime'] as String?)),
              _detail('Status', _statusLabel(_int(d['status']), _int(d['reason']))),
              _detail('Size', size > 0 ? humanBytes(size) : 'unknown'),
              if (downloaded > 0 && downloaded != size) _detail('Received', humanBytes(downloaded)),
              _detail('From', d['url'] as String? ?? '—'),
              _detail('Saved to', _location(d['localUri'] as String?)),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c),
            child: Text('Close',
                style: AppTheme.sans(size: 13, color: AppColors.accent, w: FontWeight.w600)),
          ),
        ],
      ),
    );
  }

  Widget _detail(String label, String value) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label.toUpperCase(),
              style: AppTheme.mono(size: 9, color: AppColors.textMuted, w: FontWeight.w600)),
          const SizedBox(height: 3),
          SelectableText(value,
              style: AppTheme.sans(size: 12, color: AppColors.textDim, height: 1.4)),
        ],
      ),
    );
  }

  static String _location(String? localUri) {
    if (localUri == null || localUri.isEmpty) return 'not written yet';
    return Uri.tryParse(localUri)?.toFilePath() ?? localUri;
  }

  static String _statusLabel(int status, int reason) {
    switch (status) {
      case _statusPending:
        return 'Waiting to start';
      case _statusRunning:
        return 'Downloading';
      case _statusPaused:
        return 'Paused (reason $reason)';
      case _statusSuccessful:
        return 'Complete';
      case _statusFailed:
        return 'Failed (error $reason)';
      default:
        return 'Unknown';
    }
  }

  /// What kind of file this is, for the row and the details sheet.
  static String _typeLabel(String name, String? mime) {
    final dot = name.lastIndexOf('.');
    if (dot > 0 && dot < name.length - 1) {
      final extension = name.substring(dot + 1).toUpperCase();
      if (extension.length <= 6) return extension;
    }
    if (mime != null && mime.contains('/')) return mime.split('/').last.toUpperCase();
    return 'FILE';
  }

  static IconData _iconFor(String name, String? mime) {
    final n = name.toLowerCase();
    final m = (mime ?? '').toLowerCase();
    bool any(List<String> endings) => endings.any(n.endsWith);
    if (m.startsWith('video/') || any(['.mkv', '.mp4', '.webm', '.mov', '.avi', '.m4v'])) {
      return Icons.movie_outlined;
    }
    if (m.startsWith('audio/') || any(['.mp3', '.m4a', '.flac', '.wav', '.ogg'])) {
      return Icons.music_note_outlined;
    }
    if (m.startsWith('image/') || any(['.jpg', '.jpeg', '.png', '.gif', '.webp'])) {
      return Icons.image_outlined;
    }
    if (n.endsWith('.pdf')) return Icons.picture_as_pdf_outlined;
    if (any(['.zip', '.rar', '.7z', '.tar', '.gz'])) return Icons.folder_zip_outlined;
    if (n.endsWith('.apk')) return Icons.android;
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
