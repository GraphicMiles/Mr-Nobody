import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../bridge/native_bridge.dart';
import '../browser/tab_manager.dart';
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
/// OFF in the distribution APK. The visible ⓘ panel stays in debug builds
/// only.
///
/// The black-screen regression the panel used to paper over (its poll timer
/// and log listener kept Flutter frames flowing over the platform view after
/// a page finished loading) is now handled invisibly by [SurfaceKeepAlive],
/// which sits in the browser stack and does the same frame-production job
/// with no UI at all. So the panel can be pruned from distribution builds
/// without the regression coming back.
const bool kDebugOverlayInRelease = false;

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
    // P0 fix: reduced polling frequency and only poll when overlay open or error count >0
    _syncNative();
    // Start with 15s interval, faster when open
    _poll = Timer.periodic(const Duration(seconds: 15), (_) {
      if (_open || ErrorLog.instance.count > 0) _syncNative();
    });
  }

  void _startFastPoll() {
    _poll?.cancel();
    _poll = Timer.periodic(const Duration(seconds: 5), (_) => _syncNative());
  }

  void _startSlowPoll() {
    _poll?.cancel();
    _poll = Timer.periodic(const Duration(seconds: 15), (_) {
      if (_open || ErrorLog.instance.count > 0) _syncNative();
    });
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
    final trace = await NativeBridge.guard(
      NativeBridge.debugTrace,
      const <String>[],
      '',
    );
    if (!mounted) return;
    ErrorLog.instance.setNative(entries);
    ErrorLog.instance.setNativeTrace(trace);
  }

  @override
  Widget build(BuildContext context) {
    // The developer panel and the ⓘ overlay are debug-only in a public
    // release; the black-screen fix it used to provide by accident is now
    // handled invisibly by SurfaceKeepAlive in the browser stack. See
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
                  final willOpen = !_open;
                  setState(() => _open = willOpen);
                  if (willOpen) {
                    _startFastPoll();
                    _syncNative();
                  } else {
                    _startSlowPoll();
                  }
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
        entries.length > 8 ? entries.sublist(entries.length - 8) : entries;
    final trace = ErrorLog.instance.traceLog;
    final traceTail =
        trace.length > 8 ? trace.sublist(trace.length - 8) : trace;
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
          // The lifecycle trace comes first: page start → progress → finish →
          // attach/detach → renderer gone is what catches the black screen.
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 250),
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (traceTail.isNotEmpty) ...[
                    Text(
                      'TRACE',
                      style: AppTheme.mono(
                        size: 8.5,
                        w: FontWeight.w700,
                        color: AppColors.isWarm
                            ? AppColors.overlayFaint
                            : AppColors.textMuted,
                        letterSpacing: 0.8,
                      ),
                    ),
                    const SizedBox(height: 4),
                    ...traceTail.map(
                      (e) => Padding(
                        padding: const EdgeInsets.only(bottom: 2),
                        child: Text(
                          e,
                          style: AppTheme.mono(
                            size: 9,
                            color: AppColors.isWarm
                                ? AppColors.overlayFaint
                                : AppColors.textMuted,
                            height: 1.45,
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(height: 6),
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
                  else ...[
                    Text(
                      'ERRORS',
                      style: AppTheme.mono(
                        size: 8.5,
                        w: FontWeight.w700,
                        color: AppColors.isWarm
                            ? AppColors.overlayFaint
                            : AppColors.textMuted,
                        letterSpacing: 0.8,
                      ),
                    ),
                    const SizedBox(height: 4),
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
                ],
              ),
            ),
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              _DebugAction(label: 'ERUDA', onTap: _injectEruda),
              const SizedBox(width: 6),
              _DebugAction(label: 'REBUILD', onTap: _rebuildSurface),
            ],
          ),
        ],
      ),
    );
  }

  /// Inject Eruda into the active tab straight from the panel — console,
  /// network and DOM inspection of the page that just went black.
  void _injectEruda() {
    final tab = TabManager.debugInstance?.active;
    if (tab == null) {
      ErrorLog.instance.trace('eruda: no active tab');
      AppToast.show(context, 'No active tab');
      return;
    }
    unawaited(tab.injectEruda());
    ErrorLog.instance.trace('tab ${tab.id}: eruda injected from panel');
    AppToast.show(context, 'Eruda injected into tab ${tab.id}');
  }

  /// Force a fresh platform view around the active tab's page. When the
  /// screen goes black the renderer may be dead; rebuilding the surface gives
  /// the page a new chance to paint and logs the attempt.
  void _rebuildSurface() {
    final tab = TabManager.debugInstance?.active;
    if (tab == null) {
      ErrorLog.instance.trace('rebuild: no active tab');
      AppToast.show(context, 'No active tab');
      return;
    }
    tab.rebuildSurface();
    ErrorLog.instance.trace('tab ${tab.id}: surface rebuilt from panel');
    AppToast.show(context, 'Surface rebuilt for tab ${tab.id}');
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
