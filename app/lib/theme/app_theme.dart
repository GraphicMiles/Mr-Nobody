import 'package:flutter/material.dart';

/// Monochrome black & white design system used by the production UI and its
/// committed golden tests. One accent (white), near-black surface tiers,
/// hairline borders, no shadows.
abstract final class AppColors {
  static const bg = Color(0xFF000000); // --bg / --s-0
  static const surface = Color(0xFF101010); // --s-1
  static const surface2 = Color(0xFF181818); // --s-2
  static const surface3 = Color(0xFF212121); // --s-3

  static const line = Color(0x14FFFFFF); // --line       white @ 8%
  static const lineStrong = Color(0x29FFFFFF); // --line-strong white @ 16%
  static const dim = Color(0x1FFFFFFF); // --dim        white @ 12%

  static const text = Color(0xFFFAFAFA); // --t-0
  static const textDim = Color(0xFFC4C4C7); // --t-1
  static const textFaint = Color(0xFF8A8A8F); // --t-2
  static const textMuted = Color(0xFF5C5C61); // --t-3

  static const accent = Color(0xFFFFFFFF);
  static const accentInk = Color(0xFF000000);
}

abstract final class AppSpacing {
  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 16.0;
  static const lg = 20.0;
  static const xl = 28.0;

  /// Horizontal inset of every card in the wireframe (`.card{margin:0 16px}`).
  static const cardInset = 16.0;
}

abstract final class AppTheme {
  static const sansFamily = 'Inter';
  static const monoFamily = 'JetBrainsMono';

  static ThemeData dark() {
    final base = ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: AppColors.bg,
      canvasColor: AppColors.bg,
      colorScheme: const ColorScheme.dark(
        primary: AppColors.accent,
        onPrimary: AppColors.accentInk,
        surface: AppColors.surface,
        onSurface: AppColors.text,
        outline: AppColors.lineStrong,
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
      dividerTheme: const DividerThemeData(
        color: AppColors.line,
        thickness: 1,
        space: 1,
      ),
      iconTheme: const IconThemeData(color: AppColors.textDim),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: AppColors.surface2,
        contentTextStyle: sans(size: 11.5, color: AppColors.text, w: FontWeight.w500),
        behavior: SnackBarBehavior.floating,
        shape: const StadiumBorder(side: BorderSide(color: AppColors.lineStrong)),
        insetPadding: const EdgeInsets.symmetric(horizontal: 32, vertical: 12),
      ),
    );
  }

  /// JetBrains Mono — addresses, counters, chips, section labels.
  static TextStyle mono({
    double size = 12,
    Color color = AppColors.textDim,
    FontWeight w = FontWeight.w500,
    double? height,
    double? letterSpacing,
  }) =>
      TextStyle(
        fontFamily: monoFamily,
        fontSize: size,
        color: color,
        fontWeight: w,
        height: height,
        letterSpacing: letterSpacing,
      );

  /// Inter — everything else.
  static TextStyle sans({
    double size = 14,
    Color color = AppColors.text,
    FontWeight w = FontWeight.w400,
    double? height,
    double letterSpacing = -0.01,
  }) =>
      TextStyle(
        fontFamily: sansFamily,
        fontSize: size,
        color: color,
        fontWeight: w,
        height: height,
        letterSpacing: letterSpacing,
      );
}
