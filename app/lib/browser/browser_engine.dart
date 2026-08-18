import 'dart:typed_data';

import 'package:flutter/widgets.dart';

/// The engine-independent browser capability used by the visible browser and,
/// in the agent path, by the BrowserTool.
///
/// Nothing in the UI depends on a concrete engine — it depends on this
/// interface, so the rendering surface can be replaced (V1 §8, V2 §7) without
/// touching a screen.
abstract class BrowserEngine {
  /// The page surface to place in the layout.
  Widget buildView();

  /// Whether a real engine backs this instance. False in widget tests and in
  /// the design preview, where [buildView] returns a neutral placeholder.
  bool get isAvailable;

  Future<void> loadUrl(String url);
  Future<void> reload();
  Future<void> stop();
  Future<bool> canGoBack();
  Future<void> goBack();
  Future<bool> canGoForward();
  Future<void> goForward();
  Future<String?> currentUrl();
  Future<String?> title();

  /// Re-read user settings (JavaScript, parameter stripping) into the engine.
  Future<void> applySettings();

  /// A small JPEG of the page as it looks now, for the tab grid. Null when
  /// there is nothing to capture — or when the tab is private, where a picture
  /// of what someone was reading is exactly what must not exist.
  Future<Uint8List?> captureThumbnail();

  /// Called with true/false as navigation begins/ends (loading state).
  ValueChanged<bool>? onLoadingChanged;

  /// Called with the committed URL on navigation.
  ValueChanged<String>? onUrlChanged;

  /// Called with the page title once known.
  ValueChanged<String>? onTitleChanged;

  /// Called when the main frame fails to load.
  ValueChanged<String>? onError;

  /// Called with the page's vertical scroll offset, so the UI can collapse the
  /// browser chrome while the user reads.
  ValueChanged<int>? onScroll;

  /// Called with the ads/trackers refused on the current page.
  void Function(int ads, int trackers)? onBlockedCountChanged;

  /// Called when a download starts, or fails to start ([error] set).
  void Function(String? name, String? error)? onDownload;

  /// Called with the load progress 0..100.
  ValueChanged<int>? onProgress;

  void dispose();
}
