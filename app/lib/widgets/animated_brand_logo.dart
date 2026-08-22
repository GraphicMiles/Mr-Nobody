import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import 'brand_logo.dart';

/// The ten approved movement studies. Each definition finishes at [LogoPose.rest].
enum LogoMotionType {
  springDrop,
  orbitReturn,
  figureEight,
  cornerPatrol,
  pendulum,
  squashLaunch,
  stealthPeek,
  spiralHome,
  hoverScan,
  cinematicDrift,
}

extension LogoMotionTypeLabel on LogoMotionType {
  String get label {
    switch (this) {
      case LogoMotionType.springDrop:
        return 'Spring Drop';
      case LogoMotionType.orbitReturn:
        return 'Orbit Return';
      case LogoMotionType.figureEight:
        return 'Figure Eight';
      case LogoMotionType.cornerPatrol:
        return 'Corner Patrol';
      case LogoMotionType.pendulum:
        return 'Pendulum';
      case LogoMotionType.squashLaunch:
        return 'Squash Launch';
      case LogoMotionType.stealthPeek:
        return 'Stealth Peek';
      case LogoMotionType.spiralHome:
        return 'Spiral Home';
      case LogoMotionType.hoverScan:
        return 'Hover Scan';
      case LogoMotionType.cinematicDrift:
        return 'Cinematic Drift';
    }
  }
}

@immutable
class LogoPose {
  final double x;
  final double y;
  final double rotation;
  final double scaleX;
  final double scaleY;
  final double opacity;
  final double glow;
  final double burst;

  const LogoPose({
    this.x = 0,
    this.y = 0,
    this.rotation = 0,
    this.scaleX = 1,
    this.scaleY = 1,
    this.opacity = 1,
    this.glow = 0,
    this.burst = 0,
  });

  static const rest = LogoPose();

  bool get isResting =>
      x == 0 &&
      y == 0 &&
      rotation == 0 &&
      scaleX == 1 &&
      scaleY == 1 &&
      opacity == 1 &&
      glow == 0 &&
      burst == 0;

  static LogoPose lerp(LogoPose a, LogoPose b, double t) {
    double d(double start, double end) => start + (end - start) * t;
    return LogoPose(
      x: d(a.x, b.x),
      y: d(a.y, b.y),
      rotation: d(a.rotation, b.rotation),
      scaleX: d(a.scaleX, b.scaleX),
      scaleY: d(a.scaleY, b.scaleY),
      opacity: d(a.opacity, b.opacity),
      glow: d(a.glow, b.glow),
      burst: d(a.burst, b.burst),
    );
  }
}

@immutable
class LogoMotionBeat {
  final LogoPose pose;
  final Duration duration;
  final Curve curve;

  const LogoMotionBeat(this.pose, this.duration, this.curve);
}

@immutable
class LogoMotionDefinition {
  final LogoPose initial;
  final List<LogoMotionBeat> beats;

  const LogoMotionDefinition({required this.initial, required this.beats});

  Duration get duration => beats.fold(
        Duration.zero,
        (total, beat) => total + beat.duration,
      );

  LogoPose get finalPose => beats.isEmpty ? initial : beats.last.pose;

  Animation<LogoPose> animate(AnimationController controller) {
    var previous = initial;
    final items = <TweenSequenceItem<LogoPose>>[];
    for (final beat in beats) {
      items.add(
        TweenSequenceItem(
          tween: LogoPoseTween(begin: previous, end: beat.pose).chain(
            CurveTween(curve: beat.curve),
          ),
          weight: beat.duration.inMicroseconds.toDouble(),
        ),
      );
      previous = beat.pose;
    }
    return TweenSequence<LogoPose>(items).animate(controller);
  }
}

class LogoPoseTween extends Tween<LogoPose> {
  LogoPoseTween({required super.begin, required super.end});

  @override
  LogoPose lerp(double t) => LogoPose.lerp(begin!, end!, t);
}

/// A shuffle bag: every movement appears once before reshuffling, and a bag
/// boundary cannot repeat the movement that just finished.
class LogoMotionDeck {
  final math.Random _random;
  final List<LogoMotionType> _bag = [];
  LogoMotionType? _last;

  LogoMotionDeck({math.Random? random})
      : _random = random ?? math.Random.secure();

  LogoMotionType next() {
    if (_bag.isEmpty) _refill();
    final value = _bag.removeLast();
    _last = value;
    return value;
  }

  void _refill() {
    _bag
      ..clear()
      ..addAll(LogoMotionType.values);
    for (var i = _bag.length - 1; i > 0; i--) {
      final j = _random.nextInt(i + 1);
      final value = _bag[i];
      _bag[i] = _bag[j];
      _bag[j] = value;
    }
    if (_bag.length > 1 && _bag.last == _last) {
      final value = _bag.first;
      _bag[0] = _bag.last;
      _bag[_bag.length - 1] = value;
    }
  }
}

/// Native Flutter implementation of the ten logo movements.
///
/// Only transform and opacity are animated. Every sequence lands at the exact
/// resting transform, pauses, then the next shuffle-bag movement starts.
class AnimatedBrandLogo extends StatefulWidget {
  final double size;
  final Color? color;
  final bool active;
  final Duration restDuration;
  final LogoMotionDeck? deck;
  final ValueChanged<LogoMotionType>? onMotionStarted;

  const AnimatedBrandLogo({
    super.key,
    this.size = 88,
    this.color,
    this.active = true,
    this.restDuration = const Duration(milliseconds: 1650),
    this.deck,
    this.onMotionStarted,
  });

  @override
  State<AnimatedBrandLogo> createState() => _AnimatedBrandLogoState();
}

class _AnimatedBrandLogoState extends State<AnimatedBrandLogo>
    with TickerProviderStateMixin {
  late final AnimationController _motionController;
  late final AnimationController _idleController;
  late final AnimationController _blinkController;
  late final LogoMotionDeck _deck;
  Animation<LogoPose> _pose = const AlwaysStoppedAnimation(LogoPose.rest);
  Timer? _restTimer;
  bool _reduceMotion = false;
  bool _atRest = true;

  @override
  void initState() {
    super.initState();
    _deck = widget.deck ?? LogoMotionDeck();
    _motionController = AnimationController(vsync: this)
      ..addStatusListener(_onMotionStatus);
    _idleController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1700),
    );
    _blinkController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 4200),
    );
    if (widget.active) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _startIfAllowed());
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final media = MediaQuery.maybeOf(context);
    final reduce =
        media?.disableAnimations == true || media?.accessibleNavigation == true;
    if (reduce != _reduceMotion) {
      _reduceMotion = reduce;
      if (reduce) {
        _restTimer?.cancel();
        _motionController.stop();
        _idleController.stop();
        _blinkController.stop();
        _motionController.value = 1;
      } else if (widget.active) {
        WidgetsBinding.instance.addPostFrameCallback((_) => _startIfAllowed());
      }
    }
  }

  @override
  void didUpdateWidget(AnimatedBrandLogo oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active != oldWidget.active) {
      if (widget.active) {
        _startIfAllowed();
      } else {
        _restTimer?.cancel();
        _motionController.stop();
        _idleController.stop();
        _blinkController.stop();
      }
    }
  }

  void _startIfAllowed() {
    if (!mounted ||
        !widget.active ||
        _reduceMotion ||
        _motionController.isAnimating) return;
    _playNext();
  }

  void _playNext() {
    if (!mounted || !widget.active || _reduceMotion) return;
    _restTimer?.cancel();
    _idleController
      ..stop()
      ..value = 0;
    final type = _deck.next();
    final definition = logoMotionDefinitions[type]!;
    assert(definition.finalPose.isResting, '${type.label} must return to rest');
    _motionController.duration = definition.duration;
    setState(() {
      _atRest = false;
      _pose = definition.animate(_motionController);
    });
    widget.onMotionStarted?.call(type);
    _motionController.forward(from: 0);
    if (!_blinkController.isAnimating) _blinkController.repeat();
  }

  void _onMotionStatus(AnimationStatus status) {
    if (status != AnimationStatus.completed || !mounted) return;
    setState(() => _atRest = true);
    if (!_reduceMotion && widget.active) {
      _idleController.repeat(reverse: true);
      _restTimer = Timer(widget.restDuration, _playNext);
    }
  }

  double _eyeOpen(double phase) {
    // Open for most of the 4.2s cycle, pinch shut, then recover.
    if (phase < .89 || phase >= .96) return 1;
    if (phase < .93) return 1 - ((phase - .89) / .04) * .85;
    return .15 + ((phase - .93) / .03) * .85;
  }

  @override
  void dispose() {
    _restTimer?.cancel();
    _motionController.dispose();
    _idleController.dispose();
    _blinkController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color = widget.color ?? AppColors.accent;
    return RepaintBoundary(
      child: AnimatedBuilder(
        animation: Listenable.merge([
          _motionController,
          _idleController,
          _blinkController,
        ]),
        builder: (context, _) {
          final motion = _reduceMotion ? LogoPose.rest : _pose.value;
          final idleY = _atRest && _idleController.isAnimating
              ? -5 * Curves.easeInOutSine.transform(_idleController.value)
              : 0.0;
          final idleRotation = _atRest && _idleController.isAnimating
              ? .012 * Curves.easeInOutSine.transform(_idleController.value)
              : 0.0;
          final leftEye =
              _reduceMotion ? 1.0 : _eyeOpen(_blinkController.value);
          final rightPhase = (_blinkController.value - .012 + 1) % 1;
          final rightEye = _reduceMotion ? 1.0 : _eyeOpen(rightPhase);

          final matrix = Matrix4.identity()
            ..translate(motion.x, motion.y + idleY)
            ..rotateZ(motion.rotation + idleRotation)
            ..scale(motion.scaleX, motion.scaleY);

          return SizedBox(
            width: 250,
            height: 190,
            child: Stack(
              clipBehavior: Clip.none,
              alignment: Alignment.center,
              children: [
                Opacity(
                  opacity: motion.glow.clamp(0.0, 1.0),
                  child: Transform.scale(
                    scale: .85 + motion.glow * .32,
                    child: Container(
                      width: widget.size * 1.48,
                      height: widget.size * 1.48,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        gradient: RadialGradient(
                          colors: [
                            color.withAlpha(44),
                            color.withAlpha(12),
                            color.withAlpha(0),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                if (motion.burst > 0)
                  CustomPaint(
                    size: Size.square(widget.size * 2),
                    painter: _LogoBurstPainter(
                      progress: motion.burst,
                      color: color,
                    ),
                  ),
                Opacity(
                  opacity: motion.opacity.clamp(0.0, 1.0),
                  child: Transform(
                    alignment: Alignment.center,
                    transform: matrix,
                    child: BrandLogo(
                      size: widget.size,
                      color: color,
                      leftEyeOpen: leftEye,
                      rightEyeOpen: rightEye,
                    ),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _LogoBurstPainter extends CustomPainter {
  final double progress;
  final Color color;

  const _LogoBurstPainter({required this.progress, required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    final center = size.center(Offset.zero);
    final fade = math.sin(progress.clamp(0.0, 1.0) * math.pi);
    final distance = 24 + 42 * progress;
    final paint = Paint()
      ..color = color.withAlpha((fade * 210).round())
      ..style = PaintingStyle.fill
      ..isAntiAlias = true;
    for (var i = 0; i < 8; i++) {
      final angle = -math.pi / 2 + (math.pi * 2 * i / 8);
      final radius = i.isEven ? 2.5 : 1.8;
      final point =
          center + Offset(math.cos(angle), math.sin(angle)) * distance;
      canvas.drawCircle(point, radius * fade, paint);
    }
  }

  @override
  bool shouldRepaint(_LogoBurstPainter oldDelegate) =>
      oldDelegate.progress != progress || oldDelegate.color != color;
}

const _short = Duration(milliseconds: 170);
const _medium = Duration(milliseconds: 300);
const _long = Duration(milliseconds: 430);

const Map<LogoMotionType, LogoMotionDefinition> logoMotionDefinitions = {
  LogoMotionType.springDrop: LogoMotionDefinition(
    initial:
        LogoPose(y: -220, rotation: -.38, scaleX: .35, scaleY: .35, opacity: 0),
    beats: [
      LogoMotionBeat(
          LogoPose(y: 16, rotation: .087, scaleX: 1.10, scaleY: 1.10, glow: .7),
          Duration(milliseconds: 580),
          Curves.easeOutQuart),
      LogoMotionBeat(
          LogoPose(
              y: -10, rotation: -.052, scaleX: .96, scaleY: .96, burst: .72),
          Duration(milliseconds: 220),
          Curves.easeInOut),
      LogoMotionBeat(
          LogoPose(
              y: 5, rotation: .035, scaleX: 1.03, scaleY: 1.03, burst: .25),
          Duration(milliseconds: 190),
          Curves.easeInOut),
      LogoMotionBeat(LogoPose(y: -2, rotation: -.017, scaleX: .99, scaleY: .99),
          Duration(milliseconds: 150), Curves.easeInOut),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 160), Curves.easeOut),
    ],
  ),
  LogoMotionType.orbitReturn: LogoMotionDefinition(
    initial: LogoPose.rest,
    beats: [
      LogoMotionBeat(LogoPose(scaleX: .92, scaleY: .92, rotation: -.087),
          _short, Curves.easeIn),
      LogoMotionBeat(
          LogoPose(x: 56, y: -34, rotation: .35, scaleX: 1.02, scaleY: 1.02),
          _medium,
          Curves.easeOut),
      LogoMotionBeat(
          LogoPose(x: 84, y: 13, rotation: .84), _medium, Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(x: 39, y: 56, rotation: 1.60), _medium,
          Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(x: -42, y: 55, rotation: 2.40), _medium,
          Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(x: -82, y: 7, rotation: 3.32), _medium,
          Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(x: -45, y: -39, rotation: 4.30), _medium,
          Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(x: 12, y: -21, rotation: 5.75, glow: .5), _medium,
          Curves.easeInOutSine),
      LogoMotionBeat(LogoPose.rest, _long, Curves.easeOutCubic),
    ],
  ),
  LogoMotionType.figureEight: LogoMotionDefinition(
    initial: LogoPose.rest,
    beats: [
      LogoMotionBeat(LogoPose(x: -58, y: -30, rotation: -.17),
          Duration(milliseconds: 380), Curves.easeInOutSine),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 280), Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(x: 60, y: 34, rotation: .19),
          Duration(milliseconds: 380), Curves.easeInOutSine),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 280), Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(x: 58, y: -30, rotation: .17),
          Duration(milliseconds: 380), Curves.easeInOutSine),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 280), Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(x: -60, y: 34, rotation: -.19, glow: .35),
          Duration(milliseconds: 380), Curves.easeInOutSine),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 420), Curves.easeOutCubic),
    ],
  ),
  LogoMotionType.cornerPatrol: LogoMotionDefinition(
    initial: LogoPose.rest,
    beats: [
      LogoMotionBeat(LogoPose(scaleX: .94, scaleY: .94), _short, Curves.easeIn),
      LogoMotionBeat(LogoPose(x: -66, y: -36, rotation: -.16),
          Duration(milliseconds: 340), Curves.easeOutCubic),
      LogoMotionBeat(
          LogoPose(x: 65, y: -36, rotation: .16), _long, Curves.easeInOut),
      LogoMotionBeat(LogoPose(x: 65, y: 46, rotation: .10),
          Duration(milliseconds: 360), Curves.easeInOut),
      LogoMotionBeat(
          LogoPose(x: -65, y: 46, rotation: -.10), _long, Curves.easeInOut),
      LogoMotionBeat(
          LogoPose(
              x: 4,
              y: -3,
              rotation: .017,
              scaleX: 1.035,
              scaleY: 1.035,
              burst: .62),
          _long,
          Curves.easeOutCubic),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 240), Curves.easeOutBack),
    ],
  ),
  LogoMotionType.pendulum: LogoMotionDefinition(
    initial: LogoPose(y: -61, rotation: -.54),
    beats: [
      LogoMotionBeat(LogoPose(y: -61, rotation: .45),
          Duration(milliseconds: 520), Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(y: -61, rotation: -.33),
          Duration(milliseconds: 430), Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(y: -61, rotation: .21),
          Duration(milliseconds: 340), Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(y: -61, rotation: -.10),
          Duration(milliseconds: 270), Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(y: -61, rotation: .035, glow: .35),
          Duration(milliseconds: 210), Curves.easeInOutSine),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 420), Curves.easeOutCubic),
    ],
  ),
  LogoMotionType.squashLaunch: LogoMotionDefinition(
    initial: LogoPose.rest,
    beats: [
      LogoMotionBeat(LogoPose(y: 8, scaleX: 1.13, scaleY: .82),
          Duration(milliseconds: 180), Curves.easeIn),
      LogoMotionBeat(
          LogoPose(y: -105, rotation: -.07, scaleX: .91, scaleY: 1.08),
          Duration(milliseconds: 420),
          Curves.easeOutQuart),
      LogoMotionBeat(LogoPose(y: -116, rotation: .05),
          Duration(milliseconds: 160), Curves.easeOut),
      LogoMotionBeat(
          LogoPose(y: 13, scaleX: 1.16, scaleY: .82, glow: .78, burst: .85),
          Duration(milliseconds: 400),
          Curves.easeInQuart),
      LogoMotionBeat(LogoPose(y: -18, scaleX: .96, scaleY: 1.04, burst: .3),
          Duration(milliseconds: 200), Curves.easeOutCubic),
      LogoMotionBeat(LogoPose(y: 5, scaleX: 1.025, scaleY: .98),
          Duration(milliseconds: 160), Curves.easeInOut),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 200), Curves.easeOut),
    ],
  ),
  LogoMotionType.stealthPeek: LogoMotionDefinition(
    initial: LogoPose.rest,
    beats: [
      LogoMotionBeat(LogoPose(x: -12, rotation: -.07, scaleX: .96, scaleY: .96),
          _short, Curves.easeIn),
      LogoMotionBeat(LogoPose(x: -126, y: 8, rotation: -.23, opacity: .18),
          Duration(milliseconds: 420), Curves.easeInCubic),
      LogoMotionBeat(
          LogoPose(x: -78, y: 1, rotation: .14), _medium, Curves.easeOutBack),
      LogoMotionBeat(LogoPose(x: -78, y: 1, rotation: .14),
          Duration(milliseconds: 360), Curves.linear),
      LogoMotionBeat(LogoPose(x: 126, y: -4, rotation: .23, opacity: .18),
          Duration(milliseconds: 520), Curves.easeInCubic),
      LogoMotionBeat(
          LogoPose(x: 78, y: 1, rotation: -.14), _medium, Curves.easeOutBack),
      LogoMotionBeat(LogoPose(x: 78, y: 1, rotation: -.14),
          Duration(milliseconds: 340), Curves.linear),
      LogoMotionBeat(LogoPose(x: -5, rotation: -.017),
          Duration(milliseconds: 460), Curves.easeOutCubic),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 180), Curves.easeOut),
    ],
  ),
  LogoMotionType.spiralHome: LogoMotionDefinition(
    initial: LogoPose(
        x: -72, y: -58, rotation: -3.32, scaleX: .28, scaleY: .28, opacity: 0),
    beats: [
      LogoMotionBeat(
          LogoPose(
              x: 48,
              y: -46,
              rotation: -1.92,
              scaleX: .50,
              scaleY: .50,
              opacity: .75),
          Duration(milliseconds: 380),
          Curves.easeOut),
      LogoMotionBeat(
          LogoPose(x: 70, y: 24, rotation: -.49, scaleX: .68, scaleY: .68),
          Duration(milliseconds: 340),
          Curves.easeInOutSine),
      LogoMotionBeat(
          LogoPose(x: 18, y: 62, rotation: 1.08, scaleX: .83, scaleY: .83),
          Duration(milliseconds: 310),
          Curves.easeInOutSine),
      LogoMotionBeat(
          LogoPose(x: -42, y: 31, rotation: 2.76, scaleX: .93, scaleY: .93),
          Duration(milliseconds: 290),
          Curves.easeInOutSine),
      LogoMotionBeat(
          LogoPose(
              x: -27,
              y: -18,
              rotation: 4.50,
              scaleX: 1.03,
              scaleY: 1.03,
              glow: .68,
              burst: .62),
          Duration(milliseconds: 270),
          Curves.easeInOutSine),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 420), Curves.easeOutCubic),
    ],
  ),
  LogoMotionType.hoverScan: LogoMotionDefinition(
    initial: LogoPose.rest,
    beats: [
      LogoMotionBeat(LogoPose(y: -5, scaleX: 1.02, scaleY: 1.02),
          Duration(milliseconds: 220), Curves.easeOut),
      LogoMotionBeat(LogoPose(x: -72, y: -5, rotation: -.05),
          Duration(milliseconds: 440), Curves.easeInOut),
      LogoMotionBeat(LogoPose(x: -72, y: -5, rotation: -.05),
          Duration(milliseconds: 280), Curves.linear),
      LogoMotionBeat(LogoPose(x: 72, y: -5, rotation: .05),
          Duration(milliseconds: 680), Curves.easeInOutSine),
      LogoMotionBeat(LogoPose(x: 72, y: -5, rotation: .05),
          Duration(milliseconds: 280), Curves.linear),
      LogoMotionBeat(LogoPose(y: -32, scaleX: 1.04, scaleY: 1.04, glow: .32),
          Duration(milliseconds: 420), Curves.easeInOut),
      LogoMotionBeat(LogoPose(y: -32, scaleX: 1.04, scaleY: 1.04),
          Duration(milliseconds: 250), Curves.linear),
      LogoMotionBeat(LogoPose(y: 3, scaleX: .99, scaleY: .99),
          Duration(milliseconds: 380), Curves.easeOutCubic),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 180), Curves.easeOut),
    ],
  ),
  LogoMotionType.cinematicDrift: LogoMotionDefinition(
    initial: LogoPose(
        x: -104, y: 72, rotation: -.28, scaleX: .62, scaleY: .62, opacity: 0),
    beats: [
      LogoMotionBeat(
          LogoPose(
              x: -42,
              y: 18,
              rotation: -.12,
              scaleX: .84,
              scaleY: .84,
              opacity: .65),
          Duration(milliseconds: 550),
          Curves.easeOut),
      LogoMotionBeat(
          LogoPose(
              x: 27,
              y: -30,
              rotation: .07,
              scaleX: 1.075,
              scaleY: 1.075,
              opacity: 1,
              glow: .6),
          Duration(milliseconds: 620),
          Curves.easeOutCubic),
      LogoMotionBeat(
          LogoPose(x: 27, y: -30, rotation: .07, scaleX: 1.075, scaleY: 1.075),
          Duration(milliseconds: 380),
          Curves.linear),
      LogoMotionBeat(
          LogoPose(
              x: -5,
              y: 4,
              rotation: -.026,
              scaleX: .985,
              scaleY: .985,
              burst: .45),
          Duration(milliseconds: 500),
          Curves.easeInOut),
      LogoMotionBeat(
          LogoPose.rest, Duration(milliseconds: 380), Curves.easeOutCubic),
    ],
  ),
};
