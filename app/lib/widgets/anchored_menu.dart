import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// One row of an anchored popup menu.
class MenuOption<T> {
  final T id;
  final String label;
  final IconData icon;
  final String tag;

  const MenuOption(
      {required this.id,
      required this.label,
      required this.icon,
      this.tag = ''});
}

/// `.popmenu` — a compact card that pops over the UI, right-aligned to the row
/// that opened it (Settings → Privacy profile / AI provider / Terminal).
///
/// Returns the picked id, or null if dismissed.
Future<T?> showAnchoredMenu<T>({
  required BuildContext context,
  required String title,
  required List<MenuOption<T>> options,
  T? selected,
}) {
  final box = context.findRenderObject() as RenderBox?;
  final overlay = Overlay.of(context).context.findRenderObject() as RenderBox?;
  Rect anchor = Rect.zero;
  if (box != null && overlay != null) {
    final topLeft = box.localToGlobal(Offset.zero, ancestor: overlay);
    anchor = topLeft & box.size;
  }

  return Navigator.of(context).push<T>(
    _AnchoredMenuRoute<T>(
      anchor: anchor,
      builder: (context, close) => _MenuCard(
        title: title,
        children: [
          for (final o in options)
            _MenuOptionRow(
              option: o,
              selected: o.id == selected,
              onTap: () => close(o.id),
            ),
        ],
      ),
    ),
  );
}

/// Same popup shell, but with arbitrary content (used for the inline provider
/// configuration form).
Future<T?> showAnchoredCard<T>({
  required BuildContext context,
  required String title,
  required Widget Function(BuildContext context, void Function(T? result) close)
      body,
}) {
  final box = context.findRenderObject() as RenderBox?;
  final overlay = Overlay.of(context).context.findRenderObject() as RenderBox?;
  Rect anchor = Rect.zero;
  if (box != null && overlay != null) {
    final topLeft = box.localToGlobal(Offset.zero, ancestor: overlay);
    anchor = topLeft & box.size;
  }

  return Navigator.of(context).push<T>(
    _AnchoredMenuRoute<T>(
      anchor: anchor,
      builder: (context, close) =>
          _MenuCard(title: title, children: [body(context, close)]),
    ),
  );
}

class _MenuCard extends StatelessWidget {
  final String title;
  final List<Widget> children;
  const _MenuCard({required this.title, required this.children});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: Container(
        constraints: const BoxConstraints(minWidth: 200, maxWidth: 300),
        padding: const EdgeInsets.all(4),
        decoration: BoxDecoration(
          color: AppColors.overlay,
          borderRadius: BorderRadius.circular(AppColors.isWarm ? 20 : 14),
          border: Border.all(color: AppColors.overlayLine),
          boxShadow: const [
            BoxShadow(
              color: Color(0x99000000),
              blurRadius: 40,
              offset: Offset(0, 12),
            ),
          ],
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 9, 12, 5),
              child: Text(
                title.toUpperCase(),
                style: AppTheme.mono(
                  size: 9.5,
                  color: AppColors.isWarm
                      ? AppColors.overlayMuted
                      : AppColors.textMuted,
                  w: FontWeight.w600,
                  letterSpacing: 0.76,
                ),
              ),
            ),
            ...children,
          ],
        ),
      ),
    );
  }
}

class _MenuOptionRow<T> extends StatelessWidget {
  final MenuOption<T> option;
  final bool selected;
  final VoidCallback onTap;

  const _MenuOptionRow(
      {required this.option, required this.selected, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(10),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
        decoration: BoxDecoration(
          color: selected ? AppColors.overlaySelected : Colors.transparent,
          borderRadius: BorderRadius.circular(AppColors.isWarm ? 12 : 10),
        ),
        child: Row(
          children: [
            SizedBox(
              width: 16,
              child: Icon(
                option.icon,
                size: 13,
                color: AppColors.isWarm
                    ? AppColors.overlayMuted
                    : AppColors.textFaint,
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                option.label,
                style: AppTheme.sans(
                  size: 13,
                  color: AppColors.overlayInk,
                  w: AppColors.isWarm ? FontWeight.w600 : FontWeight.w400,
                ),
              ),
            ),
            if (option.tag.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(left: 8),
                child: Text(
                  option.tag,
                  style: AppTheme.mono(
                    size: 9.5,
                    color: AppColors.isWarm
                        ? AppColors.overlayMuted
                        : AppColors.textMuted,
                  ),
                ),
              ),
            if (selected)
              Padding(
                padding: const EdgeInsets.only(left: 8),
                child: Icon(
                  Icons.check,
                  size: 12,
                  color: AppColors.isWarm
                      ? AppColors.overlayInk
                      : AppColors.accent,
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// A transparent route that positions its child under (or above) the anchor
/// rect and dismisses on an outside tap.
class _AnchoredMenuRoute<T> extends PopupRoute<T> {
  final Rect anchor;
  final Widget Function(BuildContext context, void Function(T? result) close)
      builder;

  _AnchoredMenuRoute({required this.anchor, required this.builder});

  @override
  Color? get barrierColor => null;

  @override
  bool get barrierDismissible => true;

  @override
  String? get barrierLabel => 'Dismiss menu';

  @override
  Duration get transitionDuration => const Duration(milliseconds: 140);

  @override
  Widget buildPage(BuildContext context, Animation<double> animation,
      Animation<double> secondary) {
    void close(T? result) => Navigator.of(context).pop(result);
    return FadeTransition(
      opacity: animation,
      child: CustomSingleChildLayout(
        delegate:
            _AnchorLayout(anchor, MediaQuery.of(context).viewInsets.bottom),
        child: builder(context, close),
      ),
    );
  }
}

class _AnchorLayout extends SingleChildLayoutDelegate {
  final Rect anchor;
  final double keyboardInset;

  _AnchorLayout(this.anchor, this.keyboardInset);

  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) =>
      BoxConstraints.loose(
          Size(constraints.maxWidth - 16, constraints.maxHeight - 16));

  @override
  Offset getPositionForChild(Size size, Size childSize) {
    // Right-aligned to the anchor, just below it; flips above when there is no
    // room, exactly like the wireframe's openPop().
    var left = anchor.right - childSize.width;
    left = left.clamp(
        8.0, (size.width - childSize.width - 8).clamp(8.0, double.infinity));
    var top = anchor.bottom + 6;
    final maxBottom = size.height - keyboardInset - 8;
    if (top + childSize.height > maxBottom) {
      top = anchor.top - childSize.height - 6;
    }
    if (top < 8) top = 8;
    return Offset(left, top);
  }

  @override
  bool shouldRelayout(_AnchorLayout old) =>
      old.anchor != anchor || old.keyboardInset != keyboardInset;
}
