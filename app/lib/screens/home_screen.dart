import 'dart:async';

import 'package:flutter/material.dart';
import '../bridge/native_bridge.dart';
import '../theme/app_theme.dart';
import '../widgets/animated_brand_logo.dart';
import '../widgets/common.dart';

/// Where a Home shortcut sends the user.
enum HomeShortcut { tabs, tasks, downloads, settings }

const Key kHomeLogoHeroKey = Key('home-logo-hero');
const Key kHomeSearchPillKey = Key('home-search-pill');

/// Agent Home (S2) — the centre of the product: brand mark, the one unified
/// input, live agent tasks, and shortcuts. Matches `#v-newtab` in the
/// wireframe.
class HomeScreen extends StatefulWidget {
  final ValueChanged<String> onSubmit;
  final ValueChanged<HomeShortcut> onShortcut;
  final void Function(Map<String, dynamic> task) onOpenTask;
  final ScrollController? scrollController;

  /// Whether this destination is the one on screen. Destinations live in an
  /// IndexedStack, so without this they would keep polling in the background.
  final bool isActive;

  const HomeScreen({
    super.key,
    required this.onSubmit,
    required this.onShortcut,
    required this.onOpenTask,
    this.scrollController,
    this.isActive = true,
  });

  @override
  State<HomeScreen> createState() => HomeScreenState();
}

class HomeScreenState extends State<HomeScreen> {
  final _input = TextEditingController();
  final _focus = FocusNode();
  List<Map<String, dynamic>> _active = const [];
  Timer? _poll;

  static const _liveStatuses = {
    'RUNNING',
    'QUEUED',
    'WAITING',
    'WAITING_EXTERNAL',
    'VERIFYING',
  };

  @override
  void initState() {
    super.initState();
    refresh();
    // Tasks run in background workers, so Home polls for their progress while
    // it is on screen (cheap: one in-process call, no network).
    _poll = Timer.periodic(const Duration(seconds: 3), (_) {
      if (widget.isActive) refresh();
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    _input.dispose();
    _focus.dispose();
    super.dispose();
  }

  Future<void> refresh() async {
    final tasks = await NativeBridge.guard(
      NativeBridge.recentTasks,
      const <Map<String, dynamic>>[],
      'tasks unavailable',
    );
    if (!mounted) return;
    setState(() {
      _active = tasks
          .where((t) => _liveStatuses.contains(t['status'] as String? ?? ''))
          .take(3)
          .toList();
    });
  }

  void _submit() {
    final text = _input.text.trim();
    if (text.isEmpty) return;
    _input.clear();
    _focus.unfocus();
    widget.onSubmit(text);
  }

  @override
  Widget build(BuildContext context) =>
      ScreenSurface(child: _buildBody(context));

  Widget _buildBody(BuildContext context) {
    return ListView(
      controller: widget.scrollController,
      padding: const EdgeInsets.only(bottom: 120),
      children: [
        _HomeHero(active: widget.isActive),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 18),
          child: _searchPill(),
        ),
        const SectionLabel('Active tasks'),
        AppCard(
          child: _active.isEmpty
              ? const EmptyNote('No active tasks')
              : Column(
                  children:
                      withDividers([for (final t in _active) _taskLine(t)])),
        ),
        const SectionLabel('Shortcuts'),
        AppCard(
          child: Column(
            children: withDividers([
              _shortcut(Icons.layers_rounded, 'Tabs', HomeShortcut.tabs),
              _shortcut(Icons.checklist_rounded, 'Tasks', HomeShortcut.tasks),
              _shortcut(
                  Icons.download_rounded, 'Downloads', HomeShortcut.downloads),
              _shortcut(
                  Icons.settings_rounded, 'Settings', HomeShortcut.settings),
            ]),
          ),
        ),
      ],
    );
  }

  Widget _searchPill() {
    return AnimatedContainer(
      key: kHomeSearchPillKey,
      duration: const Duration(milliseconds: 240),
      curve: Curves.easeOutCubic,
      height: 48,
      padding: const EdgeInsets.only(left: 14, right: 6),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.line),
      ),
      child: Row(
        children: [
          Icon(Icons.search, size: 16, color: AppColors.textFaint),
          const SizedBox(width: 11),
          Expanded(
            child: TextField(
              controller: _input,
              focusNode: _focus,
              style: AppTheme.sans(size: 13.5),
              cursorColor: AppColors.accent,
              decoration: InputDecoration(
                hintText: 'Ask Mr Nobody or enter URL…',
                hintStyle:
                    AppTheme.sans(size: 13.5, color: AppColors.textFaint),
                border: InputBorder.none,
                isDense: true,
              ),
              textInputAction: TextInputAction.go,
              onSubmitted: (_) => _submit(),
            ),
          ),
          GestureDetector(
            onTap: _submit,
            child: Container(
              width: 34,
              height: 34,
              decoration: BoxDecoration(
                  color: AppColors.accent, shape: BoxShape.circle),
              child: Icon(Icons.arrow_forward,
                  size: 16, color: AppColors.accentInk),
            ),
          ),
        ],
      ),
    );
  }

  Widget _taskLine(Map<String, dynamic> task) {
    final progress = ((task['progress'] as num?) ?? 0).toDouble();
    final step = task['step'] as String? ?? '';
    return ListRow(
      icon: taskIcon(task['instruction'] as String? ?? ''),
      title: task['instruction'] as String? ?? 'Task',
      subtitle: step,
      below: ProgressBar(progress / 100),
      trailing: StatusChip('${progress.round()}%'),
      onTap: () => widget.onOpenTask(task),
    );
  }

  Widget _shortcut(IconData icon, String label, HomeShortcut dest) {
    return ListRow(
      icon: icon,
      title: label,
      trailing: Icon(Icons.chevron_right, size: 14, color: AppColors.textMuted),
      onTap: () => widget.onShortcut(dest),
    );
  }
}

/// The home hero deliberately has about fifty percent more vertical breathing
/// room than the previous 132–136px treatment. The logo and every section below
/// it therefore sit lower without moving the anchored bottom navigation.
///
/// The two decorative page boxes were removed: the real app mark is now the
/// only hero element, with ten transform-only motion studies played through a
/// shuffle bag while Home is active.
///
/// Size is tuned so the logo is 40% larger and the address bar (and everything
/// below it) starts about 40% lower than the previous treatment.
class _HomeHero extends StatelessWidget {
  final bool active;

  const _HomeHero({required this.active});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      key: kHomeLogoHeroKey,
      height: 266,
      child: Center(
        child: AnimatedBrandLogo(
          size: 123,
          color: AppColors.accent,
          active: active,
        ),
      ),
    );
  }
}

/// Pick a glyph from the instruction's verb, mirroring the wireframe's
/// TASK_ICON map. Purely cosmetic — never used for routing.
IconData taskIcon(String instruction) {
  final s = instruction.toLowerCase();
  if (s.startsWith('download')) return Icons.download_rounded;
  if (s.startsWith('compare')) return Icons.balance;
  if (s.startsWith('price') ||
      s.startsWith('monitor') ||
      s.startsWith('watch')) {
    return Icons.show_chart;
  }
  if (s.startsWith('summarize') || s.startsWith('summarise')) {
    return Icons.article_outlined;
  }
  if (s.startsWith('find') || s.startsWith('search')) return Icons.search;
  if (s.startsWith('scrape') || s.startsWith('extract')) {
    return Icons.star_outline;
  }
  return Icons.checklist_rounded;
}
