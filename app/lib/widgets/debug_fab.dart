import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../bridge/native_bridge.dart';
import '../screens/dev_panel_screen.dart';
import '../screens/memory_screen.dart';
import '../state/error_log.dart';
import '../theme/app_theme.dart';
import 'toast.dart';

/// The ⓘ overlay from the wireframe: a small floating button with an error
/// badge that opens a copyable log panel.
///
/// This app ships no analytics and no crash reporter (V1 §2), so this is the
/// only channel a user has to report what went wrong — it must be present on
/// every screen and must never lie about the count.
///
/// While the distribution APK is being diagnosed (the page turning black
/// mid-browse, the bottom nav vanishing), this flag keeps the overlay in
/// release builds too, so a tester watching the defect happen live can read
/// and copy the exact failure instead of reporting "it just went black".
/// Flip it to false for the public build.
const bool kDebugOverlayInRelease = true;

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
    // The developer panel and the ⓘ overlay are debug-only in a public
    // release. While the distribution APK defect is being chased, the overlay
    // stays in so the failure is observable on a tester's device; see
    // [kDebugOverlayInRelease].
    if (kReleaseMode && !kDebugOverlayInRelease) return const SizedBox.shrink();
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
                      child: Icon(Icons.info_outline,
                          size: 15, color: AppColors.accent),
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
                          color:
                              count > 0 ? AppColors.accent : AppColors.surface3,
                          borderRadius: BorderRadius.circular(999),
                          border: Border.all(
                            color: count > 0
                                ? AppColors.accent
                                : AppColors.lineStrong,
                          ),
                        ),
                        child: Text(
                          '$count',
                          style: AppTheme.mono(
                            size: 9.5,
                            w: FontWeight.w700,
                            color: count > 0
                                ? AppColors.accentInk
                                : AppColors.textFaint,
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
    final tail =
        entries.length > 5 ? entries.sublist(entries.length - 5) : entries;
    return Container(
      padding: EdgeInsets.all(AppColors.isWarm ? 13 : 12),
      decoration: BoxDecoration(
        color: AppColors.isWarm ? AppColors.overlay : AppColors.surface2,
        borderRadius: BorderRadius.circular(AppColors.isWarm ? 20 : 14),
        border:
            AppColors.isWarm ? null : Border.all(color: AppColors.lineStrong),
        boxShadow: AppColors.isWarm
            ? const [
                BoxShadow(
                  color: Color(0x99000000),
                  blurRadius: 36,
                  offset: Offset(0, 14),
                ),
              ]
            : null,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              Text(
                'DEBUG',
                style: AppTheme.mono(
                  size: 9.5,
                  w: FontWeight.w700,
                  color:
                      AppColors.isWarm ? AppColors.overlayInk : AppColors.text,
                  letterSpacing: 0.95,
                ),
              ),
              const SizedBox(width: 10),
              Text(
                '$count ${count == 1 ? 'error' : 'errors'}',
                style: AppTheme.mono(
                  size: 9.5,
                  color: AppColors.isWarm
                      ? AppColors.overlayFaint
                      : AppColors.textFaint,
                  w: FontWeight.w600,
                ),
              ),
              const Spacer(),
              _DebugAction(
                label: 'MEM',
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const MemoryScreen()),
                ),
              ),
              const SizedBox(width: 6),
              _DebugAction(
                label: 'BENCH',
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const DevPanelScreen()),
                ),
              ),
              const SizedBox(width: 6),
              _DebugAction(
                label: 'COPY',
                onTap: () {
                  Clipboard.setData(
                      ClipboardData(text: ErrorLog.instance.dump));
                  AppToast.show(context, 'Log copied');
                },
              ),
            ],
          ),
          SizedBox(height: AppColors.isWarm ? 8 : 7),
          if (AppColors.isWarm) ...[
            Container(height: 1, color: AppColors.overlayLine),
            const SizedBox(height: 8),
          ],
          if (tail.isEmpty)
            Text(
              'no errors',
              style: AppTheme.mono(
                size: 10,
                color: AppColors.isWarm
                    ? AppColors.overlayFaint
                    : AppColors.textMuted,
                height: 1.6,
              ),
            )
          else
            ...tail.map(
              (e) => Padding(
                padding: const EdgeInsets.only(bottom: 2),
                child: Text(
                  '✗ $e',
                  style: AppTheme.mono(
                    size: 10,
                    color: AppColors.isWarm
                        ? AppColors.overlayInk
                        : AppColors.text,
                    height: 1.5,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _DebugAction extends StatelessWidget {
  final String label;
  final VoidCallback onTap;

  const _DebugAction({required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        padding: EdgeInsets.symmetric(
          horizontal: AppColors.isWarm ? 8 : 9,
          vertical: AppColors.isWarm ? 5 : 4,
        ),
        decoration: BoxDecoration(
          color:
              AppColors.isWarm ? AppColors.overlaySelected : AppColors.surface3,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
            color:
                AppColors.isWarm ? AppColors.overlayLine : AppColors.lineStrong,
          ),
        ),
        child: Text(
          label,
          style: AppTheme.mono(
            size: 8.5,
            w: FontWeight.w700,
            color: AppColors.isWarm ? AppColors.overlayInk : AppColors.accent,
          ),
        ),
      ),
    );
  }
}
