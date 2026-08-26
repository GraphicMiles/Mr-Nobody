import 'dart:async';

import 'package:flutter/material.dart';

import '../state/error_log.dart';

/// Invisible frame keeper for the browser's platform view.
///
/// Why this exists: after a page finishes loading, the indeterminate progress
/// bar is removed and Flutter can stop producing frames. When nothing forces
/// a frame, the hybrid-composition surface that hosts the WebView can be left
/// showing a stale — black — buffer: exactly the "page goes black after it
/// finishes loading" defect seen in the distribution APK.
///
/// The ⓘ debug panel used to produce those frames by accident (its poll
/// timer and its log listener rebuilt it whenever anything changed). This
/// widget does the same job deliberately and invisibly: a slow tick forces an
/// empty Flutter frame every couple of seconds, and any error-log/trace
/// activity triggers one immediately. It paints nothing and never intercepts
/// touches, so it is safe to keep in distribution builds while the debug
/// panel itself stays pruned.
class SurfaceKeepAlive extends StatefulWidget {
  const SurfaceKeepAlive({super.key, this.tick = const Duration(seconds: 2)});

  /// How often to force an empty frame while the browser is on screen.
  final Duration tick;

  @override
  State<SurfaceKeepAlive> createState() => _SurfaceKeepAliveState();
}

class _SurfaceKeepAliveState extends State<SurfaceKeepAlive> {
  Timer? _ticker;

  @override
  void initState() {
    super.initState();
    // A steady, slow frame source so the platform view is re-presented even
    // when the page is fully loaded and the UI is completely static.
    _ticker = Timer.periodic(widget.tick, (_) {
      if (mounted) setState(() {});
    });
    // Trace/error activity (tab switches, page events, surface rebuilds)
    // forces a frame the moment something happens, not a tick later.
    ErrorLog.instance.addListener(_onLogChanged);
  }

  void _onLogChanged() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _ticker?.cancel();
    ErrorLog.instance.removeListener(_onLogChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Invisible on purpose: the frame is the fix, not the paint.
    // IgnorePointer keeps the page's gestures untouched.
    return const IgnorePointer(
      child: SizedBox.expand(),
    );
  }
}
