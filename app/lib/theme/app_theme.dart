import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Monochrome black & white design system, aligned to the Mr Nobody logo.
/// One accent (white), near-black surfaces, hairline borders, no shadows.
abstract final class AppColors {
  static const bg = Color(0xFF000000);
  static const surface = Color(0xFF101010);
  static const surface2 = Color(0xFF181818);
  static const surface3 = Color(0xFF212121);
  static const line = Color(0x14FFFFFF); // hairline: white at 8%
  static const lineStrong = Color(0x29FFFFFF); // 16%
  static const text = Color(0xFFFAFAFA);
  static const textDim = Color(0xFFC4C4C7);
  static const textFaint = Color(0xFF8A8A8F);
  static const accent = Color(0xFFFFFFFF);
  static const accentInk = Color(0xFF000000);
}

abstract final class AppSpacing {
  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 16.0;
  static const lg = 20.0;
  static const xl = 28.0;
}

abstract final class AppTheme {
  static ThemeData dark() {
    final base = ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: AppColors.bg,
      colorScheme: const ColorScheme.dark(
        primary: AppColors.accent,
        onPrimary: AppColors.accentInk,
        surface: AppColors.surface,
        onSurface: AppColors.text,
        outline: AppColors.lineStrong,
      ),
      fontFamily: GoogleFonts.inter().fontFamily,
      splashFactory: InkSparkle.splashFactory,
    );

    return base.copyWith(
      textTheme: base.textTheme.apply(
        bodyColor: AppColors.text,
        displayColor: AppColors.text,
      ),
      dividerTheme: const DividerThemeData(
        color: AppColors.line,
        thickness: 1,
        space: 1,
      ),
      iconTheme: const IconThemeData(color: AppColors.textDim),
    );
  }

  /// Inter (UI) and JetBrains Mono (values/labels), matching the wireframe.
  static TextStyle mono({double size = 12, Color color = AppColors.textDim, FontWeight w = FontWeight.w500}) =>
      GoogleFonts.jetBrainsMono(fontSize: size, color: color, fontWeight: w);

  static TextStyle sans({double size = 14, Color color = AppColors.text, FontWeight w = FontWeight.w400}) =>
      GoogleFonts.inter(fontSize: size, color: color, fontWeight: w);
}
