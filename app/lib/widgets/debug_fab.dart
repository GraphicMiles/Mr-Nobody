import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../bridge/native_bridge.dart';
import '../state/error_log.dart';
import '../theme/app_theme.dart';
import 'toast.dart';

/// The ⓘ overlay from the wireframe: a small floating button with an error
/// badge that opens a copyable log panel.
///
/// This app ships no analytics and no crash reporter (V1 §2), so this is the
/// only channel a user has to report what went wrong — it must be present on
/// every screen and must never lie about the count.
class DebugOverlay extends StatefulWidget {
  /// Extra bottom offset, so the button clears a bottom bar when one is shown.
  final double bottomInset;

  const DebugOverlay({super.key, this.bottomInset = 76});

  @override
  State<DebugOverlay> createState() => _DebugOverlayState();
}

class _DebugOverlayState extends State<DebugOverlay> {
  bool _open = false;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    // Half of what goes wrong happens in the Java core and never touches a
    // Dart try/catch, so the badge has to ask the core rather than wait to be
    // told. Cheap: one in-process call.
    _syncNative();
    _poll = Timer.periodic(const Duration(seconds: 5), (_) => _syncNative());
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _syncNative() async {
    final entries = await NativeBridge.guard(
      NativeBridge.debugLog,
      const <String>[],
      // Deliberately not logged: a failure to read the log must not write to it.
      '',
    );
    if (!mounted) return;
    ErrorLog.instance.setNative(entries);
  }

  @override
  Widget build(BuildContext context) {
    final safeBottom = MediaQuery.of(context).padding.bottom;
    return AnimatedBuilder(
      animation: ErrorLog.instance,
      builder: (context, _) {
        final count = ErrorLog.instance.count;
        return Stack(
          children: [
            if (_open)
              AnimatedPositioned(
                duration: const Duration(milliseconds: 240),
                curve: Curves.easeOutCubic,
                left: 16,
                right: 16,
                bottom: safeBottom + widget.bottomInset + 48,
                child: _panel(context, count),
              ),
            AnimatedPositioned(
              duration: const Duration(milliseconds: 240),
              curve: Curves.easeOutCubic,
              right: 16,
              bottom: safeBottom + widget.bottomInset,
              child: GestureDetector(
                onTap: () {
                  setState(() => _open = !_open);
                  if (_open) _syncNative();
                },
                child: Stack(
                  clipBehavior: Clip.none,
                  children: [
                    Container(
                      width: 38,
                      height: 38,
                      decoration: BoxDecoration(
                        color: AppColors.surface2,
                        shape: BoxShape.circle,
                        border: Border.all(color: AppColors.lineStrong),
                      ),
                      child: const Icon(Icons.info_outline, size: 15, color: AppColors.accent),
                    ),
                    Positioned(
                      top: -3,
                      right: -3,
                      child: Container(
                        constraints: const BoxConstraints(minWidth: 16),
                        height: 16,
                        alignment: Alignment.center,
                        padding: const EdgeInsets.symmetric(horizontal: 4),
                        decoration: BoxDecoration(
                          color: count > 0 ? AppColors.accent : AppColors.surface3,
                          borderRadius: BorderRadius.circular(999),
                          border: Border.all(
                            color: count > 0 ? AppColors.accent : AppColors.lineStrong,
                          ),
                        ),
                        child: Text(
                          '$count',
                          style: AppTheme.mono(
                            size: 9.5,
                            w: FontWeight.w700,
                            color: count > 0 ? AppColors.accentInk : AppColors.textFaint,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _panel(BuildContext context, int count) {
    final entries = ErrorLog.instance.entries;
    final tail = entries.length > 5 ? entries.sublist(entries.length - 5) : entries;
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.surface2,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.lineStrong),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Text('DEBUG',
                  style: AppTheme.mono(size: 9.5, w: FontWeight.w700, color: AppColors.text, letterSpacing: 0.95)),
              const SizedBox(width: 10),
              Text('$count ${count == 1 ? 'error' : 'errors'}',
                  style: AppTheme.mono(size: 9.5, color: AppColors.textFaint, w: FontWeight.w600)),
              const Spacer(),
              GestureDetector(
                onTap: () {
                  Clipboard.setData(ClipboardData(text: ErrorLog.instance.dump));
                  AppToast.show(context, 'Log copied');
                },
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 4),
                  decoration: BoxDecoration(
                    color: AppColors.surface3,
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: AppColors.lineStrong),
                  ),
                  child: Text('COPY',
                      style: AppTheme.mono(size: 8.5, w: FontWeight.w700, color: AppColors.accent)),
                ),
              ),
            ],
          ),
          const SizedBox(height: 7),
          if (tail.isEmpty)
            Text('no errors', style: AppTheme.mono(size: 10, color: AppColors.textMuted, height: 1.6))
          else
            ...tail.map(
              (e) => Padding(
                padding: const EdgeInsets.only(bottom: 2),
                child: Text('✗ $e',
                    style: AppTheme.mono(size: 10, color: AppColors.text, height: 1.5)),
              ),
            ),
        ],
      ),
    );
  }
}
