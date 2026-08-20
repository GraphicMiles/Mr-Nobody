import 'dart:typed_data';

import 'package:flutter/material.dart';

import 'package:mrnobody/browser/browser_engine.dart';
import 'package:mrnobody/theme/app_theme.dart';

/// A [BrowserEngine] for tests: no platform view, no WebView, just a surface
/// that renders the current URL and records what it was asked to do.
///
/// Widget tests run with defaultTargetPlatform == android, so without this the
/// real engine would try to create an Android platform view that the test
/// harness cannot provide.
class FakeBrowserEngine implements BrowserEngine {
  final String initialUrl;
  final bool isPrivate;
  final List<String> loaded = [];
  bool reloaded = false;
  bool disposed = false;
  String? resolvedDownloadId;
  bool? resolvedDownloadAllow;

  FakeBrowserEngine({this.initialUrl = '', this.isPrivate = false}) {
    if (initialUrl.isNotEmpty) loaded.add(initialUrl);
  }

  @override
  ValueChanged<bool>? onLoadingChanged;
  @override
  ValueChanged<String>? onUrlChanged;
  @override
  ValueChanged<String>? onTitleChanged;
  @override
  ValueChanged<String>? onError;
  @override
  ValueChanged<int>? onScroll;
  @override
  void Function(int ads, int trackers)? onBlockedCountChanged;
  @override
  void Function(String? name, String? error)? onDownload;
  @override
  ValueChanged<BrowserDownloadRequest>? onDownloadApproval;
  @override
  ValueChanged<int>? onProgress;

  @override
  bool get isAvailable => false;

  @override
  Widget buildView() => Container(
        color: AppColors.bg,
        alignment: Alignment.center,
        padding: const EdgeInsets.all(28),
        child: Text(
          loaded.isEmpty ? 'New tab' : loaded.last,
          textAlign: TextAlign.center,
          style: AppTheme.mono(size: 11.5, color: AppColors.textMuted, height: 1.6),
        ),
      );

  @override
  Future<void> loadUrl(String url) async {
    loaded.add(url);
    onUrlChanged?.call(url);
  }

  @override
  Future<void> reload() async => reloaded = true;

  @override
  Future<void> stop() async {}

  @override
  Future<bool> canGoBack() async => loaded.length > 1;

  @override
  Future<void> goBack() async {}

  @override
  Future<bool> canGoForward() async => false;

  @override
  Future<void> goForward() async {}

  @override
  Future<String?> currentUrl() async => loaded.isEmpty ? null : loaded.last;

  @override
  Future<String?> title() async => null;

  @override
  Future<void> applySettings() async {}

  /// Tests can hand the tab a picture to prove the card renders it.
  Uint8List? thumbnail;

  @override
  Future<Uint8List?> captureThumbnail() async => thumbnail;

  @override
  Future<bool> resolveDownload(String requestId, bool allow) async {
    resolvedDownloadId = requestId;
    resolvedDownloadAllow = allow;
    return true;
  }

  @override
  void dispose() => disposed = true;
}
