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
/// Transfers belong to Mr Nobody, not to Android's DownloadManager. That is
/// what makes pause and resume possible, puts the file in the folder the user
/// chose rather than app-private staging, and means a download stops when the
/// app is uninstalled instead of carrying on without it.
class DownloadsScreen extends StatefulWidget {
  const DownloadsScreen({super.key});

  @override
  State<DownloadsScreen> createState() => _DownloadsScreenState();
}

class _DownloadsScreenState extends State<DownloadsScreen> {
  // DownloadRecord.Status, as the engine names it.
  static const _queued = 'QUEUED';
  static const _running = 'RUNNING';
  static const _paused = 'PAUSED';
  static const _waiting = 'WAITING';
  static const _completed = 'COMPLETED';
  static const _failed = 'FAILED';
  static const _cancelled = 'CANCELLED';

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
    if (!mounted) return;
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
      final status = _status(d);
      if (status == _completed) {
        done++;
        bytes += _int(d['size']);
      }
      if (status == _running || status == _queued) active++;
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
                // This is measured from bytes delivered by the active
                // transfers. Do not show Android's link-capability estimate:
                // OEMs commonly report a stale value such as 105 Mbps, which
                // users understandably read as the current download speed.
                MetricRow('Measured download speed', _realSpeedLabel()),
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
                ? Padding(
                    padding: const EdgeInsets.all(24),
                    child: Center(
                      child: SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                            strokeWidth: 2, color: AppColors.accent),
                      ),
                    ),
                  )
                : _items.isEmpty
                    ? const EmptyNote('No downloads yet')
                    : Column(
                        children:
                            withDividers([for (final d in _items) _row(d)])),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
            child: Text(
              'Mr Nobody downloads these itself, so they can be paused and resumed, they '
              'go to the folder you chose, and they stop if you uninstall the app. '
              'Cancelling deletes the partial file.',
              style: AppTheme.mono(
                  size: 10.5, color: AppColors.textMuted, height: 1.5),
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

  String _realSpeedLabel() {
    // Sum real measured speeds from active downloads — fixes hardcoded 105 Mbps bug
    // _speed contains smoothed bytes/sec per download id
    double total = 0;
    for (final entry in _items) {
      final id = _int(entry['id']);
      final status = _status(entry);
      if (status == _running || status == _queued) {
        total += _speed[id] ?? 0;
      }
    }
    if (total <= 0) return '—';
    return '${humanBytes(total.round())}/s';
  }

  Widget _row(Map<String, dynamic> d) {
    final id = _int(d['id']);
    final name = d['name'] as String? ?? 'download';
    final size = _int(d['size']);
    final downloaded = _int(d['downloaded']);
    final status = _status(d);
    final running = status == _running || status == _queued;
    final paused = status == _paused || status == _waiting;
    final failed = status == _failed;
    final canResume = d['canResume'] as bool? ?? false;
    // Null, not zero, when the server never said how big the file is: an
    // indeterminate bar says "working, size unknown", while 0.0 says "nothing
    // has arrived", and only one of those is true.
    final pct = (size > 0) ? (downloaded / size).clamp(0.0, 1.0) : null;
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
              child: Icon(_iconFor(name, d['mime'] as String?),
                  size: 14, color: AppColors.textDim),
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
                        style:
                            AppTheme.mono(size: 9, color: AppColors.textMuted),
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
                          _progressLabel(status, downloaded, size, speed),
                          style: AppTheme.mono(
                              size: 9.5, color: AppColors.textMuted),
                        ),
                      ],
                    )
                  else
                    Text(
                      failed
                          ? '${d['error'] ?? 'failed'} — tap for details'
                          : status == _cancelled
                              ? 'cancelled'
                              : '${humanBytes(size)} · tap to open',
                      style:
                          AppTheme.mono(size: 10, color: AppColors.textMuted),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 2),
            // Pause and resume sit on the row itself: they are the two things
            // a person wants mid-download, and burying them in a sheet is why
            // the app appeared to offer only stop and delete.
            if (running)
              _iconButton(Icons.pause, 'Pause', () => _pause(id, name))
            else if (canResume)
              _iconButton(Icons.play_arrow, 'Resume', () => _resume(id, name)),
            _iconButton(Icons.more_vert, 'More', () => _details(d)),
          ],
        ),
      ),
    );
  }

  /// A small square tap target: the row is dense and a bare icon is a
  /// 16-pixel target, which is not a button anyone can hit on a phone.
  Widget _iconButton(IconData icon, String semantic, VoidCallback onTap) {
    return Semantics(
      button: true,
      label: semantic,
      child: GestureDetector(
        onTap: onTap,
        behavior: HitTestBehavior.opaque,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 10),
          child: Icon(icon, size: 17, color: AppColors.textDim),
        ),
      ),
    );
  }

  static String _progressLabel(
      String status, int downloaded, int size, double speed) {
    if (status == _paused) return 'paused · ${humanBytes(downloaded)}';
    if (status == _waiting) return 'stopped · tap resume';
    if (status == _queued) return 'waiting…';
    final of = size > 0 ? humanBytes(size) : '—';
    final rate = speed > 0 ? ' · ${humanBytes(speed.round())}/s' : '';
    return '${humanBytes(downloaded)} / $of$rate';
  }

  static String _status(Map<String, dynamic> d) =>
      d['status'] as String? ?? _queued;

  Future<void> _pause(int id, String name) async {
    final ok = await NativeBridge.guard(
      () => NativeBridge.pauseDownload(id),
      false,
      'could not pause download',
    );
    if (!mounted) return;
    if (ok) AppToast.show(context, 'Paused $name');
    _load();
  }

  Future<void> _resume(int id, String name) async {
    final ok = await NativeBridge.guard(
      () => NativeBridge.resumeDownload(id),
      false,
      'could not resume download',
    );
    if (!mounted) return;
    AppToast.show(context, ok ? 'Resuming $name' : 'Could not resume it');
    _load();
  }

  Future<void> _openOrExplain(int id, String status, String name) async {
    if (status == _completed) {
      final opened = await NativeBridge.guard(
        () => NativeBridge.openDownload(id),
        false,
        'could not open download',
      );
      if (!mounted) return;
      AppToast.show(
          context, opened ? 'Opening $name…' : 'No app can open this file');
      return;
    }
    if (status == _failed) {
      AppToast.show(context, 'This download failed');
      return;
    }
    if (status == _paused || status == _waiting) {
      AppToast.show(context, 'Paused — press play to continue');
      return;
    }
    AppToast.show(context, 'Still downloading…');
  }

  void _details(Map<String, dynamic> d) {
    final id = _int(d['id']);
    final name = d['name'] as String? ?? 'download';
    final status = _status(d);
    final finished = status == _completed;
    final running = status == _running || status == _queued;
    final canResume = d['canResume'] as bool? ?? false;

    showMenuSheet(context, [
      if (finished)
        SheetItem(
            Icons.open_in_new, 'Open', () => _openOrExplain(id, status, name)),
      if (running) SheetItem(Icons.pause, 'Pause', () => _pause(id, name)),
      if (canResume)
        SheetItem(Icons.play_arrow, 'Resume', () => _resume(id, name)),
      SheetItem(Icons.info_outline, 'Details', () => _showDetails(d)),
      // Cancel and remove are different things: one stops a transfer, the
      // other clears the row. Collapsing them is what left the user with a
      // single destructive button.
      if (!finished && status != _cancelled)
        SheetItem(Icons.stop_circle_outlined, 'Cancel download', () async {
          final ok = await NativeBridge.guard(
            () => NativeBridge.cancelDownload(id),
            false,
            'could not cancel download',
          );
          if (!mounted) return;
          AppToast.show(
              context, ok ? 'Cancelled $name' : 'Could not cancel it');
          _load();
        }),
      SheetItem(
        Icons.delete_outline,
        finished ? 'Delete file' : 'Remove from list',
        () async {
          final removed = await NativeBridge.guard(
            () => NativeBridge.removeDownload(id, deleteFile: finished),
            false,
            'could not remove download',
          );
          if (!mounted) return;
          AppToast.show(
              context, removed ? 'Removed $name' : 'Could not remove it');
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
        backgroundColor: AppColors.overlay,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppColors.isWarm ? 24 : 16),
        ),
        title: Text(
          d['name'] as String? ?? 'download',
          style: AppTheme.sans(
            size: 16,
            color: AppColors.overlayInk,
            w: FontWeight.w700,
          ),
        ),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              _detail('Type',
                  _typeLabel(d['name'] as String? ?? '', d['mime'] as String?)),
              _detail(
                  'Status', _statusLabel(_status(d), d['error'] as String?)),
              _detail('Size', size > 0 ? humanBytes(size) : 'unknown'),
              if (downloaded > 0 && downloaded != size)
                _detail('Received', humanBytes(downloaded)),
              _detail('From', d['url'] as String? ?? '—'),
              _detail('Folder', d['folder'] as String? ?? 'Downloads (system)'),
              _detail('Saved to', _location(d['localUri'] as String?)),
              if (d['resumable'] == false && _status(d) != _completed)
                _detail('Resumable',
                    'No — this server will not continue a part-file'),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c),
            child: Text(
              'Close',
              style: AppTheme.sans(
                size: 13,
                color: AppColors.isWarm
                    ? AppColors.overlayInk
                    : AppColors.accent,
                w: AppColors.isWarm ? FontWeight.w700 : FontWeight.w600,
              ),
            ),
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
          Text(
            label.toUpperCase(),
            style: AppTheme.mono(
              size: 9,
              color: AppColors.isWarm
                  ? AppColors.overlayFaint
                  : AppColors.textMuted,
              w: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 3),
          SelectableText(
            value,
            style: AppTheme.sans(
              size: 12,
              color: AppColors.overlayMuted,
              height: 1.4,
            ),
          ),
        ],
      ),
    );
  }

  static String _location(String? localUri) {
    if (localUri == null || localUri.isEmpty) return 'not written yet';
    final uri = Uri.tryParse(localUri);
    if (uri == null) return localUri;
    // A content:// URI is not a path and pretending otherwise throws.
    if (uri.scheme == 'file') return uri.toFilePath();
    return Uri.decodeFull(localUri);
  }

  static String _statusLabel(String status, String? error) {
    switch (status) {
      case _queued:
        return 'Waiting to start';
      case _running:
        return 'Downloading';
      case _paused:
        return 'Paused';
      case _waiting:
        return 'Stopped${error == null ? '' : ' — $error'}';
      case _completed:
        return 'Complete';
      case _failed:
        return 'Failed${error == null ? '' : ' — $error'}';
      case _cancelled:
        return 'Cancelled';
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
    if (mime != null && mime.contains('/')) {
      return mime.split('/').last.toUpperCase();
    }
    return 'FILE';
  }

  static IconData _iconFor(String name, String? mime) {
    final n = name.toLowerCase();
    final m = (mime ?? '').toLowerCase();
    bool any(List<String> endings) => endings.any(n.endsWith);
    if (m.startsWith('video/') ||
        any(['.mkv', '.mp4', '.webm', '.mov', '.avi', '.m4v'])) {
      return Icons.movie_outlined;
    }
    if (m.startsWith('audio/') ||
        any(['.mp3', '.m4a', '.flac', '.wav', '.ogg'])) {
      return Icons.music_note_outlined;
    }
    if (m.startsWith('image/') ||
        any(['.jpg', '.jpeg', '.png', '.gif', '.webp'])) {
      return Icons.image_outlined;
    }
    if (n.endsWith('.pdf')) return Icons.picture_as_pdf_outlined;
    if (any(['.zip', '.rar', '.7z', '.tar', '.gz'])) {
      return Icons.folder_zip_outlined;
    }
    if (n.endsWith('.apk')) return Icons.android;
    return Icons.insert_drive_file_outlined;
  }
}

/// Human-readable byte count for the storage rows.
String humanBytes(int bytes) {
  if (bytes <= 0) return '0 B';
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).round()} KB';
  if (bytes < 1024 * 1024 * 1024) {
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
  return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
}
