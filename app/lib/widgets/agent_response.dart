import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show ImageFilter, FontFeature;

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import 'brand_logo.dart';

/// The agent's side of a conversation: loader, trace, streamed text, and the
/// tail of actions, sources and follow-ups.
///
/// Kept in one file because these pieces share a single alignment contract and
/// separating them is how it drifts. The body is 13px at 1.62 line height, so a
/// first line box is 21px, and [AgentTurn] gives the avatar a box of exactly
/// that height with the mark centred inside it. A 26px mark beside a 21px line
/// — which is what this replaced — can only line up by accident, and it rode
/// about five pixels high on every agent message in the app.
///
/// Everything here is presentational. Nothing fetches, and nothing decides what
/// the agent did; callers pass in state that came from the task record and the
/// event log.
abstract final class AgentMetrics {
  /// Body copy size. The rest of the column is derived from it.
  static const bodySize = 13.0;
  static const bodyHeight = 1.62;

  /// One line box: [bodySize] × [bodyHeight], rounded to a whole pixel.
  static const lineBox = 21.0;

  /// The avatar column. Same as [lineBox] so the mark centres on line one.
  static const avatar = 21.0;

  /// The mark inside the avatar box, inset slightly so it reads as optical
  /// centre rather than filling the box edge to edge.
  static const mark = 19.0;

  /// Avatar to text.
  static const columnGap = 10.0;

  /// Where an agent turn's text starts, measured from the gutter.
  static const indent = avatar + columnGap;

  /// Between one turn and the next.
  static const turnGap = 20.0;

  /// A block and the timestamp beneath it.
  static const stampGap = 5.0;

  /// Between paragraphs inside one answer.
  static const paragraphGap = 9.0;

  /// Horizontal inset of the whole thread.
  static const gutter = 16.0;

  /// The library's easing, used for every entrance in this file.
  static const ease = Cubic(0.23, 1, 0.32, 1);

  /// Sources, stamps, notes — secondary to the answer itself.
  static const secondaryOpacity = 0.8;
}

/// Warm amber is reserved for explicit warnings that need judgement.
const _warn = AppColors.warning;
const _warnInk = AppColors.warningInk;

// ═══════════════════════════════════════════════════════════ turn scaffold

/// One agent message: the mark, then whatever the agent produced.
///
/// Use for every agent-side block so the column keeps one indent. The avatar
/// is drawn once per turn, not once per paragraph.
class AgentTurn extends StatelessWidget {
  final Widget child;

  /// Hidden avatar, keeping the indent. For a second block in the same turn.
  final bool continued;

  const AgentTurn({super.key, required this.child, this.continued = false});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AgentMetrics.gutter),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: AgentMetrics.avatar,
            height: AgentMetrics.lineBox,
            child: continued
                ? null
                : const Center(
                    child: BrandLogo(size: AgentMetrics.mark),
                  ),
          ),
          const SizedBox(width: AgentMetrics.columnGap),
          Expanded(child: child),
        ],
      ),
    );
  }
}

/// The user's side: a filled bubble, right-aligned.
class UserTurn extends StatelessWidget {
  final String text;
  final String? stamp;

  const UserTurn({super.key, required this.text, this.stamp});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AgentMetrics.gutter),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          ConstrainedBox(
            constraints: BoxConstraints(
              maxWidth: MediaQuery.sizeOf(context).width * 0.84,
            ),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
              decoration: BoxDecoration(
                color: AppColors.accent,
                borderRadius: const BorderRadius.only(
                  topLeft: Radius.circular(15),
                  topRight: Radius.circular(15),
                  bottomLeft: Radius.circular(15),
                  bottomRight: Radius.circular(5),
                ),
              ),
              child: Text(
                text,
                style: AppTheme.sans(
                  size: 12.5,
                  w: FontWeight.w500,
                  color: AppColors.accentInk,
                  height: 1.5,
                ),
              ),
            ),
          ),
          if (stamp != null) ...[
            const SizedBox(height: AgentMetrics.stampGap),
            Text(stamp!,
                style: AppTheme.mono(size: 9, color: AppColors.textMuted)),
          ],
        ],
      ),
    );
  }
}

/// A timestamp under an agent turn, indented to sit under the text.
class AgentStamp extends StatelessWidget {
  final String text;
  const AgentStamp(this.text, {super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(
        left: AgentMetrics.gutter + AgentMetrics.indent,
        top: AgentMetrics.stampGap,
      ),
      child: Opacity(
        opacity: AgentMetrics.secondaryOpacity,
        child: Text(text,
            style: AppTheme.mono(size: 9, color: AppColors.textMuted)),
      ),
    );
  }
}

// ═══════════════════════════════════════════════════════════ shimmer label

/// Text with a highlight sweeping through it, for work in progress.
///
/// A moving label is the difference between "working" and "hung". It stops
/// completely when [active] is false rather than slowing down, because a
/// shimmer that never stops trains people to ignore it.
class ShimmerLabel extends StatefulWidget {
  final String text;
  final bool active;
  final double size;

  const ShimmerLabel(this.text,
      {super.key, this.active = true, this.size = 13});

  @override
  State<ShimmerLabel> createState() => _ShimmerLabelState();
}

class _ShimmerLabelState extends State<ShimmerLabel>
    with SingleTickerProviderStateMixin {
  // Built in initState, not a `late final` initialiser. A lazy field is
  // constructed on first read -- and if the widget is disposed before it was
  // ever painted, that first read happens inside dispose(), which builds a
  // Ticker against a deactivated element. The test suite caught exactly that.
  late final AnimationController _c;

  @override
  void initState() {
    super.initState();
    _c = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1400),
    );
    if (widget.active) _c.repeat();
  }

  @override
  void didUpdateWidget(ShimmerLabel old) {
    super.didUpdateWidget(old);
    if (widget.active && !_c.isAnimating) {
      _c.repeat();
    } else if (!widget.active && _c.isAnimating) {
      _c.stop();
    }
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final style = AppTheme.sans(
      size: widget.size,
      w: FontWeight.w500,
      color: widget.active ? AppColors.text : AppColors.textFaint,
    );
    if (!widget.active) return Text(widget.text, style: style);

    return AnimatedBuilder(
      animation: _c,
      builder: (context, _) {
        // Sweep from right to left, matching the CSS keyframes.
        final t = _c.value;
        return ShaderMask(
          blendMode: BlendMode.srcIn,
          shaderCallback: (bounds) => LinearGradient(
            colors: [
              AppColors.textMuted,
              AppColors.text,
              AppColors.textMuted,
            ],
            stops: const [0.35, 0.5, 0.65],
            begin: Alignment(-1 - 2 * (1 - t) + 1, 0),
            end: Alignment(1 + 2 * t - 1 + 2, 0),
          ).createShader(bounds),
          child: Text(widget.text, style: style),
        );
      },
    );
  }
}

// ═══════════════════════════════════════════════════════════ pixel loader

/// The 3×3 pixel grid, with a chevron wavefront driving right.
///
/// The 650ms cycle is deliberately shorter than the time the wave takes to
/// cross, so two fronts are always in flight and the grid never looks stalled.
class PixelLoader extends StatefulWidget {
  /// Circular cells instead of square ones.
  final bool round;

  const PixelLoader({super.key, this.round = false});

  @override
  State<PixelLoader> createState() => _PixelLoaderState();
}

class _PixelLoaderState extends State<PixelLoader>
    with SingleTickerProviderStateMixin {
  static const _cell = 4.0;
  static const _gap = 1.5;
  static const _period = 650;

  late final AnimationController _c;

  @override
  void initState() {
    super.initState();
    _c = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: _period),
    )..repeat();
  }

  /// Per-cell delay: column plus distance from the middle row, ×90ms.
  static int _delay(int i) {
    final r = i ~/ 3, c = i % 3;
    return (c + (r - 1).abs()) * 90;
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: _cell * 3 + _gap * 2,
      height: _cell * 3 + _gap * 2,
      child: AnimatedBuilder(
        animation: _c,
        builder: (context, _) {
          return Wrap(
            spacing: _gap,
            runSpacing: _gap,
            children: List.generate(9, (i) {
              final phase =
                  ((_c.value * _period + _delay(i)) % _period) / _period;
              // 0 → .15, .5 → 1, 1 → .15
              final o = 0.15 + 0.85 * math.sin(phase * math.pi).clamp(0.0, 1.0);
              return Container(
                width: _cell,
                height: _cell,
                decoration: BoxDecoration(
                  color: AppColors.text.withOpacity(o),
                  borderRadius: BorderRadius.circular(widget.round ? _cell : 1),
                ),
              );
            }),
          );
        },
      ),
    );
  }
}

// ═══════════════════════════════════════════════════════════ live activity

/// Loader, shimmering label, and a live elapsed timer.
class AgentWorkingLine extends StatefulWidget {
  final String label;

  /// When the work started. The timer counts up from here.
  final DateTime since;

  /// Circular cells are available for contexts that need a softer loader.
  final bool round;

  const AgentWorkingLine({
    super.key,
    required this.label,
    required this.since,
    this.round = false,
  });

  @override
  State<AgentWorkingLine> createState() => _AgentWorkingLineState();
}

class _AgentWorkingLineState extends State<AgentWorkingLine> {
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    _tick = Timer.periodic(const Duration(milliseconds: 100), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  String get _elapsed {
    final s = DateTime.now().difference(widget.since).inMilliseconds / 1000.0;
    if (s < 60) return '${s.toStringAsFixed(1)}s';
    return '${s ~/ 60}m ${(s % 60).toStringAsFixed(1)}s';
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        PixelLoader(round: widget.round),
        const SizedBox(width: 10),
        Flexible(child: ShimmerLabel(widget.label)),
        const SizedBox(width: 10),
        Text(
          _elapsed,
          style: AppTheme.mono(size: 12, color: AppColors.textFaint)
              .copyWith(fontFeatures: const [FontFeature.tabularFigures()]),
        ),
      ],
    );
  }
}

// ═══════════════════════════════════════════════════════════ trace

/// One semantic activity, with tool outcomes nested in [detail].
class TraceStep {
  /// The user-facing verb phrase selected for this task.
  final String label;

  /// The argument — a domain, a query, a filename. Not the verb again.
  final String? chip;

  /// Monospace the chip, for anything machine-shaped.
  final bool mono;

  /// Status metric shown beneath the verb (`6 candidates`, `2 sources`).
  /// [chip] remains as a compatibility alias for older callers.
  final String? metric;

  /// Duration text, already formatted (`0.8s`).
  final String? duration;

  /// Lines revealed when the row is tapped.
  final List<String> detail;

  /// Monospace those detail lines.
  final bool detailMono;

  /// This step is the one currently running.
  final bool running;

  /// Outcome flags. They are separate from [running] so old call sites remain
  /// source-compatible while the event-driven renderer can distinguish a
  /// recovered fallback, failure, approval wait and cancellation.
  final bool denied;
  final bool recovered;
  final bool failed;
  final bool waiting;
  final bool cancelled;

  const TraceStep({
    required this.label,
    this.chip,
    this.metric,
    this.mono = false,
    this.duration,
    this.detail = const [],
    this.detailMono = false,
    this.running = false,
    this.denied = false,
    this.recovered = false,
    this.failed = false,
    this.waiting = false,
    this.cancelled = false,
  });

  String? get displayedMetric => metric ?? chip;
}

/// The live pipeline and expandable "Thought for 4 seconds" trace.
///
/// Auto-expands while running and collapses once settled, but stays tappable
/// either way — the working is always retrievable, never lost. The chevron is
/// always drawn: the source library reveals it on hover, which never fires on
/// a touch screen, so a hover-gated affordance is an invisible one here.
class AgentTrace extends StatefulWidget {
  final List<TraceStep> steps;

  /// Still working. Live traces stay expanded; the separate
  /// [AgentWorkingLine] owns the current activity and elapsed time.
  final bool running;

  /// Past tense with a duration, e.g. `Thought for 4 seconds`.
  final String doneLabel;

  const AgentTrace({
    super.key,
    required this.steps,
    required this.running,
    required this.doneLabel,
  });

  @override
  State<AgentTrace> createState() => _AgentTraceState();
}

class _AgentTraceState extends State<AgentTrace> {
  bool? _manual;
  final Set<int> _openRows = {};

  bool get _expanded => _manual ?? widget.running;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (!widget.running)
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => setState(() => _manual = !_expanded),
            child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 3),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.auto_awesome,
                    size: 14, color: AppColors.textMuted),
                const SizedBox(width: 7),
                Flexible(
                  child: ShimmerLabel(widget.doneLabel, active: false),
                ),
                const SizedBox(width: 5),
                AnimatedRotation(
                  turns: _expanded ? 0 : -0.25,
                  duration: const Duration(milliseconds: 300),
                  curve: AgentMetrics.ease,
                  child: Icon(Icons.keyboard_arrow_down,
                      size: 15, color: AppColors.textMuted),
                ),
              ],
            ),
          ),
        ),
        ClipRect(
          child: AnimatedAlign(
            alignment: Alignment.topLeft,
            heightFactor: _expanded ? 1 : 0,
            duration: const Duration(milliseconds: 300),
            curve: AgentMetrics.ease,
            child: AnimatedOpacity(
              opacity: _expanded ? 1 : 0,
              duration: const Duration(milliseconds: 220),
              child: Container(
                margin: const EdgeInsets.only(top: 3),
                padding: const EdgeInsets.only(left: 4),
                decoration: BoxDecoration(
                  border: Border(
                      left: BorderSide(color: AppColors.line, width: 1)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    for (var i = 0; i < widget.steps.length; i++)
                      _row(i, widget.steps[i]),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _row(int i, TraceStep step) {
    // The active stage previews its decision/tool detail automatically. As
    // soon as the next stage starts, `running` becomes false and this detail
    // collapses back to the one-line summary unless the user opens it.
    final open = step.running || _openRows.contains(i);
    final metric = step.displayedMetric;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: step.detail.isEmpty
              ? null
              : () => setState(() {
                    open ? _openRows.remove(i) : _openRows.add(i);
                  }),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 5),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Padding(
                  padding: const EdgeInsets.only(top: 1),
                  child: SizedBox(
                    width: 16,
                    height: 16,
                    child: Center(
                      child: AnimatedSwitcher(
                        duration: const Duration(milliseconds: 220),
                        switchInCurve: AgentMetrics.ease,
                        child: KeyedSubtree(
                          key: ValueKey(_statusLabel(step)),
                          child: _marker(step),
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 7),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        step.label,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: AppTheme.sans(
                            size: 12.5, w: FontWeight.w500, height: 1.25),
                      ),
                      const SizedBox(height: 3),
                      Wrap(
                        spacing: 5,
                        runSpacing: 2,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          Text(
                            _statusLabel(step),
                            style: AppTheme.mono(
                                size: 9.5, color: AppColors.textMuted),
                          ),
                          if (metric != null && metric.isNotEmpty) ...[
                            Text('·',
                                style: AppTheme.mono(
                                    size: 9.5, color: AppColors.textMuted)),
                            Text(
                              metric,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: step.mono
                                  ? AppTheme.mono(
                                      size: 9.5, color: AppColors.textFaint)
                                  : AppTheme.sans(
                                      size: 10.5, color: AppColors.textFaint),
                            ),
                          ],
                          if (step.duration != null && step.duration!.isNotEmpty) ...[
                            Text('·',
                                style: AppTheme.mono(
                                    size: 9.5, color: AppColors.textMuted)),
                            Text(step.duration!,
                                style: AppTheme.mono(
                                    size: 9.5, color: AppColors.textMuted)),
                          ],
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        ClipRect(
          child: AnimatedAlign(
            key: ValueKey('trace-detail-$i'),
            alignment: Alignment.topLeft,
            heightFactor: open ? 1 : 0,
            duration: const Duration(milliseconds: 260),
            curve: AgentMetrics.ease,
            child: Container(
              margin: const EdgeInsets.only(left: 24, bottom: 4),
              padding: const EdgeInsets.fromLTRB(10, 8, 10, 8),
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: AppColors.line),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final line in step.detail)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 1),
                      child: Text(
                        line,
                        style: step.detailMono
                            ? AppTheme.mono(
                                size: 10.5,
                                color: AppColors.textFaint,
                                height: 1.55)
                            : AppTheme.sans(
                                size: 11.5,
                                color: AppColors.textFaint,
                                height: 1.55),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _marker(TraceStep step) {
    if (step.running) return const _Spinner();
    if (step.denied) {
      return Icon(Icons.block, size: 14, color: AppColors.textMuted);
    }
    if (step.waiting) {
      return Icon(
          Icons.hourglass_top, size: 14, color: AppColors.textMuted);
    }
    if (step.recovered) {
      return Icon(Icons.refresh, size: 14, color: AppColors.textMuted);
    }
    if (step.failed) {
      return Icon(
          Icons.error_outline, size: 14, color: AppColors.textMuted);
    }
    if (step.cancelled) {
      return Icon(Icons.close, size: 14, color: AppColors.textMuted);
    }
    return Icon(Icons.check, size: 14, color: AppColors.textMuted);
  }

  String _statusLabel(TraceStep step) {
    if (step.running) return 'working';
    if (step.denied) return 'denied';
    if (step.waiting) return 'waiting';
    if (step.recovered) return 'recovered';
    if (step.failed) return 'failed';
    if (step.cancelled) return 'cancelled';
    return 'done';
  }
}

class _Spinner extends StatefulWidget {
  const _Spinner();

  @override
  State<_Spinner> createState() => _SpinnerState();
}

class _SpinnerState extends State<_Spinner>
    with SingleTickerProviderStateMixin {
  late final AnimationController _c;

  @override
  void initState() {
    super.initState();
    _c = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 700),
    )..repeat();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return RotationTransition(
      turns: _c,
      child: SizedBox(
        width: 11,
        height: 11,
        child: CircularProgressIndicator(
          strokeWidth: 1.5,
          color: AppColors.textDim,
          backgroundColor: AppColors.lineStrong,
        ),
      ),
    );
  }
}

// ═══════════════════════════════════════════════════════════ streamed text

/// A word in a streamed answer.
class StreamToken {
  final String text;

  /// Render as an inline citation chip instead of a word.
  final AgentSource? cite;

  final bool bold;
  final bool italic;

  const StreamToken(this.text,
      {this.cite, this.bold = false, this.italic = false});
}

/// A page the agent read.
class AgentSource {
  final String title;
  final String domain;
  final String url;

  const AgentSource(
      {required this.title, required this.domain, required this.url});

  /// First letter of the domain, for the drawn mark.
  ///
  /// Deliberately drawn rather than fetched: a favicon is an un-gated network
  /// request per domain, which is exactly what the privacy audit's egress rule
  /// exists to prevent, and it would leak which sources a user is reading to
  /// every one of those hosts.
  String get initial =>
      domain.isEmpty ? '?' : domain[0].toUpperCase();
}

/// The answer, revealed word by word with each word resolving out of blur.
///
/// [tokens] is the whole answer; [visible] is how much has arrived. The parent
/// owns the timing, so the same widget serves a real token stream and a timed
/// reveal of an already-complete string without knowing which it is.
class StreamedAnswer extends StatelessWidget {
  final List<StreamToken> tokens;
  final int visible;

  /// Draw the blinking caret. False once the answer is complete.
  final bool caret;

  final void Function(AgentSource source)? onSourceTap;

  const StreamedAnswer({
    super.key,
    required this.tokens,
    required this.visible,
    this.caret = false,
    this.onSourceTap,
  });

  @override
  Widget build(BuildContext context) {
    final shown = tokens.take(visible.clamp(0, tokens.length)).toList();
    return Text.rich(
      TextSpan(
        children: [
          for (var i = 0; i < shown.length; i++)
            if (shown[i].cite != null)
              WidgetSpan(
                alignment: PlaceholderAlignment.middle,
                child: _CiteChip(
                  source: shown[i].cite!,
                  onTap: onSourceTap,
                  // Only animate while the parent says the reveal is still
                  // active. A restored, already-complete answer must paint at
                  // full opacity on its first frame (and in deterministic
                  // golden tests), not replay the last three entrances.
                  animate: caret && i >= shown.length - 3,
                ),
              )
              else
              WidgetSpan(
                alignment: PlaceholderAlignment.baseline,
                baseline: TextBaseline.alphabetic,
                child: _Word(
                  text: shown[i].text,
                  bold: shown[i].bold,
                  italic: shown[i].italic,
                  animate: caret && i >= shown.length - 3,
                ),
              ),
          if (caret)
            const WidgetSpan(
              alignment: PlaceholderAlignment.middle,
              child: _Caret(),
            ),
        ],
      ),
      style: AppTheme.sans(
        size: AgentMetrics.bodySize,
        height: AgentMetrics.bodyHeight,
      ),
    );
  }
}

class _Word extends StatelessWidget {
  final String text;
  final bool animate;
  final bool bold;
  final bool italic;

  const _Word({
    required this.text,
    required this.animate,
    this.bold = false,
    this.italic = false,
  });

  @override
  Widget build(BuildContext context) {
    final child = Text(
      '$text ',
      style: AppTheme.sans(
        size: AgentMetrics.bodySize,
        height: AgentMetrics.bodyHeight,
        w: bold ? FontWeight.w700 : FontWeight.w400,
      ).copyWith(fontStyle: italic ? FontStyle.italic : FontStyle.normal),
    );
    if (!animate) return child;

    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: 1),
      duration: const Duration(milliseconds: 420),
      curve: const Cubic(0.22, 0.61, 0.25, 1),
      builder: (context, t, c) => Opacity(
        opacity: t,
        child: ImageFiltered(
          // 4px of blur resolving to nothing, matching stream-in.
          imageFilter: ImageFilter.blur(
            sigmaX: 4 * (1 - t),
            sigmaY: 4 * (1 - t),
            tileMode: TileMode.decal,
          ),
          child: c,
        ),
      ),
      child: child,
    );
  }
}

class _Caret extends StatefulWidget {
  const _Caret();

  @override
  State<_Caret> createState() => _CaretState();
}

class _CaretState extends State<_Caret> with SingleTickerProviderStateMixin {
  late final AnimationController _c;

  @override
  void initState() {
    super.initState();
    _c = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1000),
    )..repeat();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _c,
      builder: (context, _) => Opacity(
        // Hard on/off, like steps(1) — a fading caret reads as a glow.
        opacity: _c.value < 0.5 ? 1 : 0,
        child: Container(
          width: 2,
          height: 13,
          margin: const EdgeInsets.only(left: 2),
          decoration: BoxDecoration(
            color: AppColors.text,
            borderRadius: BorderRadius.circular(1),
          ),
        ),
      ),
    );
  }
}

/// The inline source chip: drawn mark plus domain, at the claim it supports.
class _CiteChip extends StatelessWidget {
  final AgentSource source;
  final void Function(AgentSource)? onTap;
  final bool animate;

  const _CiteChip({required this.source, this.onTap, this.animate = false});

  @override
  Widget build(BuildContext context) {
    final chip = GestureDetector(
      onTap: onTap == null ? null : () => onTap!(source),
      child: Container(
        height: 17,
        margin: const EdgeInsets.symmetric(horizontal: 1),
        padding: const EdgeInsets.only(left: 4, right: 5),
        decoration: BoxDecoration(
          color: AppColors.surface2,
          borderRadius: BorderRadius.circular(5),
          border: Border.all(color: AppColors.line),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            _SourceMark(source: source, size: 11, radius: 3),
            const SizedBox(width: 4),
            Text(source.domain,
                style: AppTheme.mono(size: 10.5, color: AppColors.textDim)),
          ],
        ),
      ),
    );
    if (!animate) return chip;
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: 1),
      duration: const Duration(milliseconds: 250),
      curve: AgentMetrics.ease,
      builder: (context, t, c) =>
          Opacity(opacity: t, child: Transform.scale(scale: 0.92 + 0.08 * t, child: c)),
      child: chip,
    );
  }
}

/// A drawn lettermark standing in for a favicon. Never a network request.
class _SourceMark extends StatelessWidget {
  final AgentSource source;
  final double size;
  final double radius;

  const _SourceMark(
      {required this.source, required this.size, required this.radius});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: AppColors.surface3,
        borderRadius: BorderRadius.circular(radius),
      ),
      child: Text(
        source.initial,
        style: AppTheme.sans(
          size: size * 0.62,
          w: FontWeight.w700,
          color: AppColors.textDim,
        ),
      ),
    );
  }
}

// ═══════════════════════════════════════════════════════════ warning

/// The figure check's note: a claim the sources do not support.
///
/// Amber, and the only amber in the app. It is a warning about the answer, so
/// it sits with the answer — above the divider and the action row, not beneath
/// them where it would read as a footnote about the buttons.
class FigureWarning extends StatelessWidget {
  final List<String> figures;

  const FigureWarning({super.key, required this.figures});

  @override
  Widget build(BuildContext context) {
    if (figures.isEmpty) return const SizedBox.shrink();
    final one = figures.length == 1;
    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
      decoration: BoxDecoration(
        color: _warn.withOpacity(0.07),
        borderRadius: BorderRadius.circular(9),
        border: Border.all(color: _warn.withOpacity(0.26)),
      ),
      child: Text.rich(
        TextSpan(
          children: [
            TextSpan(
              text: one
                  ? '⚠︎ One figure isn\'t on the pages I read: '
                  : '⚠︎ These figures aren\'t on the pages I read: ',
              style: AppTheme.sans(
                  size: 11.5, w: FontWeight.w600, color: _warn, height: 1.55),
            ),
            TextSpan(
              text: figures.join(', '),
              style:
                  AppTheme.mono(size: 11, color: _warnInk).copyWith(height: 1.55),
            ),
            TextSpan(
              text: one
                  ? '. It may be worked out from the sources — worth checking.'
                  : '. They may be worked out from the sources — worth checking.',
              style: AppTheme.sans(size: 11.5, color: _warnInk, height: 1.55),
            ),
          ],
        ),
      ),
    );
  }
}

// ═══════════════════════════════════════════════════════════ tail

/// Copy / retry / up / down, plus the stacked source avatars.
class AgentActions extends StatefulWidget {
  final List<AgentSource> sources;
  final VoidCallback? onCopy;
  final VoidCallback? onRetry;
  final void Function(AgentSource)? onSourceTap;

  const AgentActions({
    super.key,
    this.sources = const [],
    this.onCopy,
    this.onRetry,
    this.onSourceTap,
  });

  @override
  State<AgentActions> createState() => _AgentActionsState();
}

class _AgentActionsState extends State<AgentActions> {
  bool _open = false;
  int _vote = 0; // -1 down, 0 none, 1 up

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: AgentMetrics.secondaryOpacity,
      child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            _btn(Icons.content_copy_outlined, widget.onCopy),
            _btn(Icons.refresh, widget.onRetry),
            _btn(Icons.thumb_up_outlined, () => setState(() => _vote = _vote == 1 ? 0 : 1),
                on: _vote == 1),
            _btn(Icons.thumb_down_outlined,
                () => setState(() => _vote = _vote == -1 ? 0 : -1),
                on: _vote == -1),
            if (widget.sources.isNotEmpty) ...[
              const SizedBox(width: 6),
              GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: () => setState(() => _open = !_open),
                child: Padding(
                  padding:
                      const EdgeInsets.only(left: 4, right: 6, top: 3, bottom: 3),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      SizedBox(
                        height: 15,
                        width: 15.0 + (widget.sources.length - 1) * 11,
                        child: Stack(
                          children: [
                            for (var i = 0; i < widget.sources.length; i++)
                              Positioned(
                                left: i * 11.0,
                                child: Container(
                                  decoration: BoxDecoration(
                                    shape: BoxShape.circle,
                                    color: AppColors.bg,
                                  ),
                                  padding: const EdgeInsets.all(1.5),
                                  child: _SourceMark(
                                    source: widget.sources[i],
                                    size: 15,
                                    radius: 8,
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                      const SizedBox(width: 6),
                      Text(
                        '${widget.sources.length} '
                        '${widget.sources.length == 1 ? "source" : "sources"}',
                        style:
                            AppTheme.sans(size: 12, color: AppColors.textDim),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ],
        ),
        ClipRect(
          child: AnimatedAlign(
            alignment: Alignment.topLeft,
            heightFactor: _open ? 1 : 0,
            duration: const Duration(milliseconds: 280),
            curve: AgentMetrics.ease,
            child: Container(
              margin: const EdgeInsets.only(top: 7),
              padding: const EdgeInsets.all(4),
              decoration: BoxDecoration(
                color: AppColors.surface2,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.line),
              ),
              child: Column(
                children: [
                  for (final s in widget.sources)
                    GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: widget.onSourceTap == null
                          ? null
                          : () => widget.onSourceTap!(s),
                      child: Padding(
                        padding: const EdgeInsets.all(6),
                        child: Row(
                          children: [
                            _SourceMark(source: s, size: 16, radius: 4),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(s.title,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: AppTheme.sans(
                                      size: 12, color: AppColors.textDim)),
                            ),
                            const SizedBox(width: 8),
                            Text(s.domain,
                                style: AppTheme.mono(
                                    size: 10, color: AppColors.textMuted)),
                          ],
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ],
      ),
    );
  }

  Widget _btn(IconData icon, VoidCallback? onTap, {bool on = false}) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: SizedBox(
        width: 26,
        height: 26,
        child: Icon(icon,
            size: 14, color: on ? AppColors.text : AppColors.textMuted),
      ),
    );
  }
}

/// Suggested next messages, offered as taps rather than typing.
class AgentFollowUps extends StatelessWidget {
  final List<String> items;
  final void Function(String) onTap;

  const AgentFollowUps({super.key, required this.items, required this.onTap});

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 13),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Follow-ups',
              style: AppTheme.sans(
                  size: 11.5, w: FontWeight.w500, color: AppColors.textFaint)),
          const SizedBox(height: 3),
          for (var i = 0; i < items.length; i++)
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => onTap(items[i]),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 8),
                decoration: BoxDecoration(
                  border: i == items.length - 1
                      ? null
                      : Border(
                          bottom: BorderSide(color: AppColors.line)),
                ),
                child: Row(
                  children: [
                    Icon(Icons.subdirectory_arrow_left,
                        size: 12, color: AppColors.textMuted),
                    const SizedBox(width: 9),
                    Expanded(
                      child: Text(items[i],
                          style: AppTheme.sans(size: 12.5, height: 1.4)),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
    );
  }
}
