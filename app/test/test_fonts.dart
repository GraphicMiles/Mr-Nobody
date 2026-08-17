import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// Load the bundled brand fonts (and Material's icon font) so widget tests and
/// goldens measure real glyph metrics instead of the test harness's square
/// placeholder font — otherwise every label is far wider than reality and
/// layouts "overflow" only in tests.
Future<void> loadTestFonts() async {
  TestWidgetsFlutterBinding.ensureInitialized();

  final flutterRoot = Platform.environment['FLUTTER_ROOT'];
  if (flutterRoot != null) {
    final icons = File('$flutterRoot/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf');
    if (icons.existsSync()) {
      await (FontLoader('MaterialIcons')
            ..addFont(icons.readAsBytes().then((b) => ByteData.view(b.buffer))))
          .load();
    }
  }

  const families = {
    'Inter': [
      'assets/fonts/Inter-400.ttf',
      'assets/fonts/Inter-600.ttf',
      'assets/fonts/Inter-700.ttf',
      'assets/fonts/Inter-800.ttf',
    ],
    'JetBrainsMono': [
      'assets/fonts/JetBrainsMono-500.ttf',
      'assets/fonts/JetBrainsMono-600.ttf',
      'assets/fonts/JetBrainsMono-700.ttf',
    ],
  };

  for (final family in families.entries) {
    final loader = FontLoader(family.key);
    for (final path in family.value) {
      final file = File(path);
      if (file.existsSync()) {
        loader.addFont(file.readAsBytes().then((b) => ByteData.view(b.buffer)));
      }
    }
    await loader.load();
  }
}
