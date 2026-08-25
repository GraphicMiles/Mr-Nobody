import 'package:flutter/material.dart';

/// One complete semantic colour set. Shared widgets read these tokens so every
/// screen and overlay switches as one unit; the few warm-only composition and
/// radius changes explicitly preserve the original Classic geometry.
class AppPalette {
  final Color bg;
  final Color bgSoft;
  final Color surface;
  final Color surface2;
  final Color surface3;
  final Color line;
  final Color lineStrong;
  final Color dim;
  final Color glow;
  final Color text;
  final Color textDim;
  final Color textFaint;
  final Color textMuted;
  final Color accent;
  final Color accentSoft;
  final Color accentInk;
  final Color overlay;
  final Color overlayInk;
  final Color overlayMuted;
  final Color overlayFaint;
  final Color overlayLine;
  final Color overlaySelected;

  const AppPalette({
    required this.bg,
    required this.bgSoft,
    required this.surface,
    required this.surface2,
    required this.surface3,
    required this.line,
    required this.lineStrong,
    required this.dim,
    required this.glow,
    required this.text,
    required this.textDim,
    required this.textFaint,
    required this.textMuted,
    required this.accent,
    required this.accentSoft,
    required this.accentInk,
    required this.overlay,
    required this.overlayInk,
    required this.overlayMuted,
    required this.overlayFaint,
    required this.overlayLine,
    required this.overlaySelected,
  });
}

/// Runtime semantic colours.
///
/// Flutter rebuilds the complete [MaterialApp] when the saved theme changes.
/// [use] runs before that rebuild, so app-owned widgets and ThemeData read the
/// same palette. The browser platform view keeps the website's own colours.
abstract final class AppColors {
  static const classicId = 'classic';
  static const warmId = 'warm';

  static const classic = AppPalette(
    bg: Color(0xFF000000),
    bgSoft: Color(0xFF000000),
    surface: Color(0xFF101010),
    surface2: Color(0xFF181818),
    surface3: Color(0xFF212121),
    line: Color(0x14FFFFFF),
    lineStrong: Color(0x29FFFFFF),
    dim: Color(0x1FFFFFFF),
    glow: Color(0x00000000),
    text: Color(0xFFFAFAFA),
    textDim: Color(0xFFC4C4C7),
    textFaint: Color(0xFF8A8A8F),
    textMuted: Color(0xFF5C5C61),
    accent: Color(0xFFFFFFFF),
    accentSoft: Color(0xFFC4C4C7),
    accentInk: Color(0xFF000000),
    overlay: Color(0xFF101010),
    overlayInk: Color(0xFFFAFAFA),
    overlayMuted: Color(0xFFC4C4C7),
    overlayFaint: Color(0xFF8A8A8F),
    overlayLine: Color(0x29FFFFFF),
    overlaySelected: Color(0xFF181818),
  );

  static const warm = AppPalette(
    bg: Color(0xFF0C0D0E),
    bgSoft: Color(0xFF111212),
    surface: Color(0xFF151515),
    surface2: Color(0xFF1D1C1B),
    surface3: Color(0xFF292621),
    line: Color(0x1FF1DAC6),
    lineStrong: Color(0x3BF1DAC6),
    dim: Color(0x24F1DAC6),
    glow: Color(0x0AF1DAC6),
    text: Color(0xFFF8F0E8),
    textDim: Color(0xFFC6BDB4),
    textFaint: Color(0xFF8C8580),
    textMuted: Color(0xFF615D59),
    accent: Color(0xFFF1DAC6),
    accentSoft: Color(0xFFD8BFA8),
    accentInk: Color(0xFF181512),
    overlay: Color(0xFFF1DAC6),
    overlayInk: Color(0xFF181512),
    overlayMuted: Color(0xA6181512),
    overlayFaint: Color(0x80181512),
    overlayLine: Color(0x24181512),
    overlaySelected: Color(0x14181512),
  );

  static AppPalette _active = classic;

  /// Unknown and retired values stay on the original monochrome theme.
  static void use(String? id) {
    _active = id?.toLowerCase() == warmId ? warm : classic;
  }

  static Color get bg => _active.bg;
  static Color get bgSoft => _active.bgSoft;
  static Color get surface => _active.surface;
  static Color get surface2 => _active.surface2;
  static Color get surface3 => _active.surface3;
  static Color get line => _active.line;
  static Color get lineStrong => _active.lineStrong;
  static Color get dim => _active.dim;
  static Color get glow => _active.glow;
  static Color get text => _active.text;
  static Color get textDim => _active.textDim;
  static Color get textFaint => _active.textFaint;
  static Color get textMuted => _active.textMuted;
  static Color get accent => _active.accent;
  static Color get accentSoft => _active.accentSoft;
  static Color get accentInk => _active.accentInk;
  static Color get overlay => _active.overlay;
  static Color get overlayInk => _active.overlayInk;
  static Color get overlayMuted => _active.overlayMuted;
  static Color get overlayFaint => _active.overlayFaint;
  static Color get overlayLine => _active.overlayLine;
  static Color get overlaySelected => _active.overlaySelected;
  static bool get isWarm => identical(_active, warm);

  // Semantic state is deliberately stable across themes. These retain the
  // production colours so success, danger and warning never change meaning.
  static const success = Color(0xFF3DDC84);
  static const warning = Color(0xFFE8B339);
  static const warningInk = Color(0xFFF0CF8A);
  static const danger = Color(0xFFE5484D);
}

abstract final class AppSpacing {
  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 16.0;
  static const lg = 20.0;
  static const xl = 28.0;
  static const cardInset = 16.0;
}

abstract final class AppTheme {
  static const sansFamily = 'Inter';
  static const monoFamily = 'JetBrainsMono';

  static BoxDecoration get backdrop => AppColors.isWarm
      ? BoxDecoration(
          color: AppColors.bg,
          gradient: RadialGradient(
            center: const Alignment(0, -0.8),
            radius: 0.45,
            colors: [AppColors.glow, AppColors.bg.withAlpha(0)],
          ),
        )
      : BoxDecoration(color: AppColors.bg);

  /// Original production appearance. Kept as an explicit public constructor
  /// for tests and for users who prefer the monochrome interface.
  static ThemeData dark() => forTheme(AppColors.classicId);

  static ThemeData forTheme(String? id) {
    AppColors.use(id);
    final base = ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: AppColors.bg,
      canvasColor: AppColors.bg,
      colorScheme: ColorScheme.dark(
        primary: AppColors.accent,
        onPrimary: AppColors.accentInk,
        surface: AppColors.surface,
        onSurface: AppColors.text,
        outline: AppColors.lineStrong,
        error: AppColors.danger,
        onError: AppColors.accentInk,
      ),
      fontFamily: sansFamily,
      splashFactory: InkSparkle.splashFactory,
    );

    return base.copyWith(
      textTheme: base.textTheme.apply(
        bodyColor: AppColors.text,
        displayColor: AppColors.text,
        fontFamily: sansFamily,
      ),
      dividerTheme: DividerThemeData(
        color: AppColors.line,
        thickness: 1,
        space: 1,
      ),
      iconTheme: IconThemeData(color: AppColors.textDim),
      dialogTheme: AppColors.isWarm
          ? DialogTheme(
              backgroundColor: AppColors.overlay,
              surfaceTintColor: Colors.transparent,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(24),
              ),
              titleTextStyle: sans(
                size: 17,
                color: AppColors.overlayInk,
                w: FontWeight.w700,
              ),
              contentTextStyle: sans(
                size: 12.5,
                color: AppColors.overlayMuted,
                height: 1.5,
              ),
            )
          : const DialogTheme(),
      snackBarTheme: SnackBarThemeData(
        backgroundColor:
            AppColors.isWarm ? AppColors.overlay : AppColors.surface2,
        contentTextStyle: sans(
          size: 11.5,
          color: AppColors.isWarm ? AppColors.overlayInk : AppColors.text,
          w: AppColors.isWarm ? FontWeight.w600 : FontWeight.w500,
        ),
        behavior: SnackBarBehavior.floating,
        shape: AppColors.isWarm
            ? const StadiumBorder()
            : StadiumBorder(side: BorderSide(color: AppColors.lineStrong)),
        insetPadding: const EdgeInsets.symmetric(horizontal: 32, vertical: 12),
      ),
    );
  }

  // P2 size optimization: only 400/600/700 bundled, map others to nearest available
  static FontWeight _mapSansWeight(FontWeight w) {
    if (w == FontWeight.w500) return FontWeight.w400;
    if (w == FontWeight.w800) return FontWeight.w700;
    if (w == FontWeight.w900) return FontWeight.w700;
    if (w == FontWeight.w300) return FontWeight.w400;
    if (w == FontWeight.w200) return FontWeight.w400;
    if (w == FontWeight.w100) return FontWeight.w400;
    return w;
  }

  static FontWeight _mapMonoWeight(FontWeight w) {
    if (w == FontWeight.w500) return FontWeight.w400;
    if (w == FontWeight.w700) return FontWeight.w600;
    if (w == FontWeight.w800) return FontWeight.w600;
    if (w == FontWeight.w900) return FontWeight.w600;
    return w;
  }

  static TextStyle mono({
    double size = 12,
    Color? color,
    FontWeight w = FontWeight.w500,
    double? height,
    double? letterSpacing,
  }) =>
      TextStyle(
        fontFamily: monoFamily,
        fontSize: size,
        color: color ?? AppColors.textDim,
        fontWeight: _mapMonoWeight(w),
        height: height,
        letterSpacing: letterSpacing,
      );

  static TextStyle sans({
    double size = 14,
    Color? color,
    FontWeight w = FontWeight.w400,
    double? height,
    double letterSpacing = -0.01,
  }) =>
      TextStyle(
        fontFamily: sansFamily,
        fontSize: size,
        color: color ?? AppColors.text,
        fontWeight: _mapSansWeight(w),
        height: height,
        letterSpacing: letterSpacing,
      );
}
