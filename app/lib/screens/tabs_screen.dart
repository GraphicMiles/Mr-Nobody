import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// Sessions / Tabs (S3) — a 2-column card grid with thumbnail previews, PRIVATE
/// badge, and a dashed "+" card. Sequential order; only a tap jumps tabs.
class TabsScreen extends StatelessWidget {
  const TabsScreen({super.key});

  static const _tabs = [
    ('Docs', false),
    ('Mail', false),
    ('News', true),
    ('Agent · laptops', false),
    ('Rotten Tomatoes', false),
  ];

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _header(context),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 18, vertical: 10),
          child: Row(
            children: [
              _GridButton(icon: Icons.add, accent: true),
              SizedBox(width: 8),
              _GridButton(icon: Icons.visibility_off),
              Spacer(),
              _CountBadge('5'),
              Spacer(),
              _GridButton(icon: Icons.grid_view_rounded),
            ],
          ),
        ),
        Expanded(
          child: GridView.builder(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 20),
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 2,
              mainAxisSpacing: 10,
              crossAxisSpacing: 10,
              childAspectRatio: 0.82,
            ),
            itemCount: _tabs.length + 1,
            itemBuilder: (c, i) {
              if (i == _tabs.length) return const _NewTabCard();
              final (title, isPrivate) = _tabs[i];
              return _TabCard(title: title, isPrivate: isPrivate);
            },
          ),
        ),
      ],
    );
  }

  Widget _header(BuildContext context) {
    return Container(
      color: AppColors.surface,
      padding: EdgeInsets.only(
        left: 8,
        top: 8 + MediaQuery.of(context).padding.top,
        right: 12,
        bottom: 8,
      ),
      child: Row(
        children: [
          IconButton(
            onPressed: () {},
            icon: const Icon(Icons.chevron_left, color: AppColors.textDim, size: 26),
          ),
          Text('Sessions', style: AppTheme.sans(size: 16, w: FontWeight.w700)),
        ],
      ),
    );
  }
}

class _TabCard extends StatelessWidget {
  final String title;
  final bool isPrivate;
  const _TabCard({required this.title, required this.isPrivate});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.line),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(10, 9, 8, 7),
            child: Row(
              children: [
                Container(width: 14, height: 14, decoration: BoxDecoration(color: AppColors.surface3, borderRadius: BorderRadius.circular(4))),
                const SizedBox(width: 7),
                Expanded(
                  child: Text(title, style: AppTheme.sans(size: 12, w: FontWeight.w600), maxLines: 1, overflow: TextOverflow.ellipsis),
                ),
                const Icon(Icons.close, size: 13, color: AppColors.textFaint),
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
                        _line(0.7),
                        const SizedBox(height: 8),
                        _line(0.5),
                      ],
                    ),
                  ),
                  if (isPrivate)
                    Positioned(
                      top: 6,
                      right: 6,
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                        decoration: BoxDecoration(
                          color: AppColors.accent,
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Text('PRIVATE', style: AppTheme.mono(size: 7.5, color: AppColors.accentInk, w: FontWeight.w700)),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _line(double w) {
    return FractionallySizedBox(
      widthFactor: w,
      child: Container(height: 4, decoration: BoxDecoration(color: AppColors.surface3, borderRadius: BorderRadius.circular(2))),
    );
  }
}

class _NewTabCard extends StatelessWidget {
  const _NewTabCard();

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.line),
      ),
      child: const Icon(Icons.add, size: 26, color: AppColors.textFaint),
    );
  }
}

class _GridButton extends StatelessWidget {
  final IconData icon;
  final bool accent;
  const _GridButton({required this.icon, this.accent = false});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 36,
      height: 36,
      decoration: BoxDecoration(
        color: accent ? AppColors.accent : AppColors.surface,
        borderRadius: BorderRadius.circular(11),
        border: Border.all(color: AppColors.line),
      ),
      child: Icon(icon, size: 15, color: accent ? AppColors.accentInk : AppColors.textDim),
    );
  }
}

class _CountBadge extends StatelessWidget {
  final String count;
  const _CountBadge(this.count);

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12),
      height: 36,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(11),
        border: Border.all(color: AppColors.lineStrong),
      ),
      child: Center(child: Text(count, style: AppTheme.sans(size: 12, w: FontWeight.w700))),
    );
  }
}
