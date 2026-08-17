import 'dart:async';
import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// `.toast` — a small pill that fades in above the bottom bar and leaves on its
/// own. Used for the short confirmations the wireframe shows ("Tab closed",
/// "Bookmarked", "Profile: Strict").
class AppToast {
  static OverlayEntry? _entry;
  static Timer? _timer;

  static void show(BuildContext context, String message) {
    final overlay = Overlay.maybeOf(context, rootOverlay: true);
    if (overlay == null) return;

    dismiss();

    final bottomInset = MediaQuery.of(context).padding.bottom;
    final entry = OverlayEntry(
      builder: (_) => Positioned(
        left: 16,
        right: 16,
        bottom: bottomInset + 96,
        child: IgnorePointer(
          child: Center(
            child: _ToastPill(message: message),
          ),
        ),
      ),
    );
    _entry = entry;
    overlay.insert(entry);
    _timer = Timer(const Duration(milliseconds: 1800), dismiss);
  }

  static void dismiss() {
    _timer?.cancel();
    _timer = null;
    _entry?.remove();
    _entry = null;
  }
}

class _ToastPill extends StatefulWidget {
  final String message;
  const _ToastPill({required this.message});

  @override
  State<_ToastPill> createState() => _ToastPillState();
}

class _ToastPillState extends State<_ToastPill> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 180),
  )..forward();

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return FadeTransition(
      opacity: _c,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 9),
        decoration: BoxDecoration(
          color: AppColors.surface2,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(color: AppColors.lineStrong),
        ),
        child: Text(
          widget.message,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: AppTheme.sans(size: 11.5, w: FontWeight.w500),
        ),
      ),
    );
  }
}
