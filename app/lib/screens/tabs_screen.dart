import 'package:flutter/material.dart';
import '../browser/browser_tab.dart';
import '../browser/tab_manager.dart';
import '../theme/app_theme.dart';
import '../widgets/toast.dart';

/// Sessions / Tabs (S3) — the real tab list from the shared [TabManager]:
/// action row, tab search, 2-column card grid with PRIVATE badges, and a
/// dashed "new tab" card. Matches `#v-tabs` in the wireframe.
class TabsScreen extends StatefulWidget {
  final TabManager tabs;

  /// Open the currently active tab in the browser.
  final VoidCallback onOpenTab;

  const TabsScreen({super.key, required this.tabs, required this.onOpenTab});

  @override
  State<TabsScreen> createState() => _TabsScreenState();
}

class _TabsScreenState extends State<TabsScreen> {
  final _search = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.tabs,
      builder: (context, _) {
        final all = widget.tabs.tabs;
        final visible = _query.isEmpty
            ? all
            : all.where((t) => t.label.toLowerCase().contains(_query)).toList();

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SafeArea(bottom: false, child: _actionRow(all.length)),
            _searchField(),
            Expanded(
              child: GridView.builder(
                padding: const EdgeInsets.fromLTRB(16, 6, 16, 120),
                gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: 2,
                  mainAxisSpacing: 10,
                  crossAxisSpacing: 10,
                  childAspectRatio: 0.84,
                ),
                itemCount: visible.length + 1,
                itemBuilder: (context, i) {
                  if (i == visible.length) {
                    return _NewTabCard(
                      label: all.isEmpty ? 'No tabs — tap to open one' : null,
                      onTap: () {
                        widget.tabs.newTab();
                        AppToast.show(context, 'New tab');
                        widget.onOpenTab();
                      },
                    );
                  }
                  final tab = visible[i];
                  return _TabCard(
                    tab: tab,
                    active: tab.id == widget.tabs.active?.id,
                    onTap: () {
                      widget.tabs.selectById(tab.id);
                      widget.onOpenTab();
                    },
                    onClose: () {
                      widget.tabs.closeById(tab.id);
                      AppToast.show(context, 'Tab closed');
                    },
                  );
                },
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _actionRow(int count) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: Row(
        children: [
          _roundButton(
            Icons.add,
            accent: true,
            tooltip: 'New tab',
            onTap: () {
              widget.tabs.newTab();
              AppToast.show(context, 'New tab');
              widget.onOpenTab();
            },
          ),
          const SizedBox(width: 8),
          _roundButton(
            Icons.visibility_off_outlined,
            tooltip: 'New private tab',
            onTap: () {
              widget.tabs.newTab(isPrivate: true);
              AppToast.show(context, 'Private tab opened');
              widget.onOpenTab();
            },
          ),
          const Spacer(),
          Text('$count', style: AppTheme.mono(size: 13, w: FontWeight.w700, color: AppColors.text)),
          const Spacer(),
          _roundButton(
            Icons.grid_view_rounded,
            tooltip: 'Close all tabs',
            onTap: count == 0
                ? null
                : () {
                    widget.tabs.closeAll();
                    AppToast.show(context, 'All tabs closed');
                  },
          ),
        ],
      ),
    );
  }

  Widget _roundButton(IconData icon, {bool accent = false, VoidCallback? onTap, String? tooltip}) {
    final button = GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: Container(
        width: 34,
        height: 34,
        decoration: BoxDecoration(
          color: accent ? AppColors.accent : Colors.transparent,
          shape: BoxShape.circle,
          border: Border.all(color: accent ? AppColors.accent : AppColors.line),
        ),
        child: Icon(
          icon,
          size: 15,
          color: accent
              ? AppColors.accentInk
              : (onTap == null ? AppColors.textMuted : AppColors.textDim),
        ),
      ),
    );
    return tooltip == null ? button : Tooltip(message: tooltip, child: button);
  }

  Widget _searchField() {
    return Container(
      height: 38,
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 8),
      padding: const EdgeInsets.symmetric(horizontal: 12),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.line),
      ),
      child: Row(
        children: [
          const Icon(Icons.search, size: 14, color: AppColors.textFaint),
          const SizedBox(width: 9),
          Expanded(
            child: TextField(
              controller: _search,
              style: AppTheme.sans(size: 13, color: AppColors.textDim),
              cursorColor: AppColors.accent,
              decoration: InputDecoration(
                isDense: true,
                border: InputBorder.none,
                hintText: 'Search your tabs',
                hintStyle: AppTheme.sans(size: 13, color: AppColors.textFaint),
              ),
              onChanged: (v) => setState(() => _query = v.trim().toLowerCase()),
            ),
          ),
          if (_query.isNotEmpty)
            GestureDetector(
              onTap: () {
                _search.clear();
                setState(() => _query = '');
              },
              child: const Icon(Icons.close, size: 14, color: AppColors.textFaint),
            ),
        ],
      ),
    );
  }
}

class _TabCard extends StatelessWidget {
  final BrowserTab tab;
  final bool active;
  final VoidCallback onTap;
  final VoidCallback onClose;

  const _TabCard({
    required this.tab,
    required this.active,
    required this.onTap,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: active ? AppColors.lineStrong : AppColors.line),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 9, 6, 7),
              child: Row(
                children: [
                  Container(
                    width: 14,
                    height: 14,
                    decoration: BoxDecoration(
                      color: AppColors.surface3,
                      borderRadius: BorderRadius.circular(4),
                    ),
                  ),
                  const SizedBox(width: 7),
                  Expanded(
                    child: Text(
                      tab.label,
                      style: AppTheme.sans(size: 12, w: FontWeight.w600),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  GestureDetector(
                    onTap: onClose,
                    behavior: HitTestBehavior.opaque,
                    child: const Padding(
                      padding: EdgeInsets.all(4),
                      child: Icon(Icons.close, size: 13, color: AppColors.textFaint),
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: Container(
                margin: const EdgeInsets.fromLTRB(8, 0, 8, 8),
                decoration: BoxDecoration(
                  color: AppColors.surface2,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Stack(
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(9),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          _mockLine(0.72),
                          const SizedBox(height: 8),
                          _mockLine(0.48),
                        ],
                      ),
                    ),
                    if (tab.isPrivate)
                      Positioned(
                        top: 6,
                        right: 6,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                          decoration: BoxDecoration(
                            color: AppColors.accent,
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Text(
                            'PRIVATE',
                            style: AppTheme.mono(
                              size: 7.5,
                              color: AppColors.accentInk,
                              w: FontWeight.w700,
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _mockLine(double widthFactor) {
    return FractionallySizedBox(
      widthFactor: widthFactor,
      child: Container(
        height: 4,
        decoration: BoxDecoration(
          color: AppColors.surface3,
          borderRadius: BorderRadius.circular(2),
        ),
      ),
    );
  }
}

class _NewTabCard extends StatelessWidget {
  final VoidCallback onTap;
  final String? label;

  const _NewTabCard({required this.onTap, this.label});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: DottedBorderBox(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.add, size: 20, color: AppColors.textMuted),
            if (label != null) ...[
              const SizedBox(height: 8),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 10),
                child: Text(
                  label!,
                  textAlign: TextAlign.center,
                  style: AppTheme.sans(size: 11, color: AppColors.textMuted),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Dashed-border container — the wireframe's `border-style:dashed` cards.
class DottedBorderBox extends StatelessWidget {
  final Widget child;
  const DottedBorderBox({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      painter: _DashedBorderPainter(),
      child: Center(child: child),
    );
  }
}

class _DashedBorderPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = AppColors.lineStrong
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1;
    final rrect = RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(14));
    final path = Path()..addRRect(rrect);
    const dash = 5.0;
    const gap = 4.0;
    for (final metric in path.computeMetrics()) {
      var distance = 0.0;
      while (distance < metric.length) {
        final next = (distance + dash).clamp(0.0, metric.length);
        canvas.drawPath(metric.extractPath(distance, next), paint);
        distance = next + gap;
      }
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
