import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

/// The Mr Nobody mark — hat + brim + glasses — drawn from the same 64x64
/// design grid as the wireframe SVG and the Android launcher icon
/// (`res/drawable/ic_launcher_foreground.xml`). Vector, so it stays crisp at
/// any size and tints with [color].
class BrandLogo extends StatelessWidget {
  final double size;
  final Color? color;
  final double leftEyeOpen;
  final double rightEyeOpen;

  const BrandLogo({
    super.key,
    this.size = 64,
    this.color,
    this.leftEyeOpen = 1,
    this.rightEyeOpen = 1,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: CustomPaint(
        painter: _BrandLogoPainter(
          color ?? AppColors.accent,
          leftEyeOpen.clamp(.08, 1).toDouble(),
          rightEyeOpen.clamp(.08, 1).toDouble(),
        ),
      ),
    );
  }
}

class _BrandLogoPainter extends CustomPainter {
  final Color color;
  final double leftEyeOpen;
  final double rightEyeOpen;

  const _BrandLogoPainter(this.color, this.leftEyeOpen, this.rightEyeOpen);

  @override
  void paint(Canvas canvas, Size size) {
    // Everything below is expressed on the 64x64 grid, then scaled.
    final k = size.shortestSide / 64.0;
    canvas.save();
    canvas.scale(k, k);

    final fill = Paint()
      ..color = color
      ..style = PaintingStyle.fill
      ..isAntiAlias = true;
    final stroke = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3
      ..strokeCap = StrokeCap.round
      ..isAntiAlias = true;

    // Hat crown: M18 30 L24 15 C26 20 30 21 32 12 C34 21 38 20 40 15 L46 30 Z
    final crown = Path()
      ..moveTo(18, 30)
      ..lineTo(24, 15)
      ..cubicTo(26, 20, 30, 21, 32, 12)
      ..cubicTo(34, 21, 38, 20, 40, 15)
      ..lineTo(46, 30)
      ..close();
    canvas.drawPath(crown, fill);

    // Brim: rounded bar under the crown.
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        const Rect.fromLTWH(14, 30, 36, 4),
        const Radius.circular(2),
      ),
      fill,
    );

    // Glasses: each lens can pinch vertically for the blink animation while
    // retaining the exact launcher-mark geometry at eyeOpen == 1.
    void drawLens(Offset center, double openness) {
      canvas.save();
      canvas.translate(center.dx, center.dy);
      canvas.scale(1, openness);
      canvas.drawCircle(Offset.zero, 7, stroke);
      canvas.restore();
    }

    drawLens(const Offset(24, 42), leftEyeOpen);
    drawLens(const Offset(40, 42), rightEyeOpen);
    canvas.drawLine(const Offset(31, 42), const Offset(33, 42), stroke);

    canvas.restore();
  }

  @override
  bool shouldRepaint(_BrandLogoPainter old) =>
      old.color != color ||
      old.leftEyeOpen != leftEyeOpen ||
      old.rightEyeOpen != rightEyeOpen;
}
