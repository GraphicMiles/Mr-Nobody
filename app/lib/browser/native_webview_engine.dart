import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

import '../bridge/native_bridge.dart';
import '../state/error_log.dart';
import '../theme/app_theme.dart';
import 'browser_engine.dart';

/// The visible browser surface: our own Android WebView, hosted as a platform
/// view (`MrNobodyWebView` on the Java side).
///
/// It is deliberately not a third-party WebView plugin. Blocking an ad means
/// refusing a sub-resource request before it leaves the device, which only
/// `WebViewClient.shouldInterceptRequest` can do — and no plugin exposes it.
/// Hosting the view ourselves keeps the filter engine, the cookie policy, the
/// download listener and the JavaScript switch on the native side, where they
/// can actually be enforced.
class NativeWebViewEngine implements BrowserEngine {
  static const String viewType = 'mrnobody/webview';

  final String initialUrl;
  final bool isPrivate;

  /// Latest committed/requested URL, used if native clear-data teardown forces
  /// this tab's platform view to be created again.
  late String _recreationUrl;

  /// The tab this surface belongs to. The native side keeps one WebView per
  /// tab id and re-attaches it when the platform view is rebuilt, so leaving
  /// the browser and coming back shows the same page instead of a black
  /// surface with nothing loaded.
  final int tabId;

  MethodChannel? _channel;
  bool _disposed = false;
  Future<void>? _nativeRelease;

  /// Commands issued before the platform view exists are replayed on creation.
  final List<_PendingCall> _pending = [];

  @override
  ValueChanged<bool>? onLoadingChanged;
  @override
  ValueChanged<String>? onUrlChanged;
  @override
  ValueChanged<String>? onTitleChanged;
  @override
  ValueChanged<Uint8List>? onIconChanged;
  @override
  ValueChanged<String>? onError;
  @override
  ValueChanged<int>? onScroll;
  @override
  void Function(int ads, int trackers)? onBlockedCountChanged;
  @override
  ValueChanged<String>? onNotice;
  @override
  void Function(String? name, String? error)? onDownload;
  @override
  ValueChanged<BrowserDownloadRequest>? onDownloadApproval;
  @override
  ValueChanged<int>? onProgress;

  NativeWebViewEngine({this.tabId = -1, this.initialUrl = '', this.isPrivate = false}) {
    _recreationUrl = initialUrl;
  }

  String get recreationUrl => _recreationUrl;

  @override
  bool get isAvailable => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  ValueKey<String> get platformViewKey => ValueKey<String>('mrnobody-webview-$tabId');

  // ------------------------------------------------------------------ view

  @override
  Widget buildView() {
    if (!isAvailable) return const _UnavailableSurface();
    return RepaintBoundary(
      child: PlatformViewLink(
      // Without a tab-specific key Flutter reuses the previous platform-view
      // controller when + selects a new tab, leaving the old page on screen.
      key: platformViewKey,
      viewType: viewType,
      surfaceFactory: (context, controller) => AndroidViewSurface(
        controller: controller as AndroidViewController,
        hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        // The page gets the gestures: scrolling and text selection belong to
        // the web content, not to the Flutter chrome around it.
        gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
          Factory<OneSequenceGestureRecognizer>(() => EagerGestureRecognizer()),
        },
      ),
      onCreatePlatformView: (params) {
        final controller = PlatformViewsService.initExpensiveAndroidView(
          id: params.id,
          viewType: viewType,
          layoutDirection: TextDirection.ltr,
          creationParams: <String, Object>{
            'url': _recreationUrl,
            'private': isPrivate,
            'tabId': tabId,
          },
          creationParamsCodec: const StandardMessageCodec(),
          onFocus: () => params.onFocusChanged(true),
        )
          ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
          ..addOnPlatformViewCreatedListener(_attach)
          ..create();
        return controller;
      },
    ),
    );
  }

  void _attach(int id) {
    if (_disposed) return;
    // P0 fix: avoid race where old handler cleared before new ready.
    // Create new channel first, then swap.
    final newChannel = MethodChannel(
        tabId >= 0 ? '${viewType}_tab_$tabId' : '${viewType}_$id');
    newChannel.setMethodCallHandler(_handleEvent);
    final oldChannel = _channel;
    _channel = newChannel;
    // Now safe to clear old handler
    if (oldChannel != null && oldChannel != newChannel) {
      oldChannel.setMethodCallHandler(null);
    }
    // Replay pending with retry logic — don't lose commands issued during rapid switches
    if (_pending.isNotEmpty) {
      final toReplay = List<_PendingCall>.from(_pending);
      _pending.clear();
      for (final call in toReplay) {
        newChannel.invokeMethod<void>(call.method, call.arguments).catchError((e) {
          // If replay fails (renderer gone), keep for next attach if still relevant
          if (call.method == 'loadUrl' || call.method == 'reload') {
            _pending.add(call);
          }
          _report(e);
        });
      }
    }
  }

  // ---------------------------------------------------------------- events

  Future<dynamic> _handleEvent(MethodCall call) async {
    final args = (call.arguments as Map?)?.cast<String, dynamic>() ?? const {};
    switch (call.method) {
      case 'onNavigation':
        final url = args['url'] as String?;
        if (url != null && url.isNotEmpty && url != 'about:blank') {
          _recreationUrl = url;
          onUrlChanged?.call(url);
        }
        final title = args['title'] as String?;
        if (title != null && title.isNotEmpty) onTitleChanged?.call(title);
        final loading = args['loading'] as bool?;
        if (loading != null) onLoadingChanged?.call(loading);
        break;
      case 'onTitle':
        final title = args['title'] as String?;
        if (title != null && title.isNotEmpty) onTitleChanged?.call(title);
        break;
      case 'onIcon':
        final icon = args['icon'];
        if (icon is Uint8List && icon.isNotEmpty) onIconChanged?.call(icon);
        break;
      case 'onProgress':
        onProgress?.call((args['progress'] as num?)?.toInt() ?? 0);
        break;
      case 'onError':
        onLoadingChanged?.call(false);
        onError?.call(args['error'] as String? ?? 'Network error');
        break;
      case 'onScroll':
        onScroll?.call((args['y'] as num?)?.toInt() ?? 0);
        break;
      case 'onBlocked':
        onBlockedCountChanged?.call(
          (args['ads'] as num?)?.toInt() ?? 0,
          (args['trackers'] as num?)?.toInt() ?? 0,
        );
        break;
      case 'onNotice':
        final message = args['message'] as String?;
        if (message != null && message.isNotEmpty) onNotice?.call(message);
        break;
      case 'onDownload':
        onDownload?.call(args['name'] as String?, args['error'] as String?);
        break;
      case 'onDownloadApproval':
        final id = args['id'] as String? ?? '';
        if (id.isNotEmpty) {
          onDownloadApproval?.call(BrowserDownloadRequest(
            id: id,
            name: args['name'] as String? ?? 'download',
            mime: args['mime'] as String? ?? '',
            sourceHost: args['host'] as String? ?? '',
            warning: args['warning'] as String? ?? 'This file may be harmful.',
          ));
        }
        break;
      case 'onConsole':
        onConsole?.call(args);
        break;
    }
    return null;
  }

  // -------------------------------------------------------------- commands

  Future<T?> _invoke<T>(String method, [Map<String, dynamic>? arguments]) async {
    if (_disposed) return null;
    final channel = _channel;
    if (channel == null) {
      // The view is still being created; replay once it is.
      // P0 fix: deduplicate loadUrl — only keep latest
      if (method == 'loadUrl') {
        _pending.removeWhere((p) => p.method == 'loadUrl');
      }
      _pending.add(_PendingCall(method, arguments));
      return null;
    }
    try {
      return await channel.invokeMethod<T>(method, arguments);
    } catch (e) {
      _report(e);
      // P0 fix: on MissingPluginException (channel handler gone), queue for replay
      final msg = e.toString();
      if (msg.contains('MissingPluginException') || msg.contains('notImplemented')) {
        if (method == 'loadUrl' || method == 'reload' || method == 'applySettings') {
          _pending.removeWhere((p) => p.method == method && method != 'applySettings');
          _pending.add(_PendingCall(method, arguments));
        }
      }
      return null;
    }
  }

  void _report(Object error) => ErrorLog.instance.add('webview: $error');

  @override
  Future<void> loadUrl(String url) async {
    _recreationUrl = url;
    onUrlChanged?.call(url);
    onLoadingChanged?.call(true);
    await _invoke<void>('loadUrl', {'url': url});
  }

  @override
  Future<void> reload() => _invoke<void>('reload').then((_) {});

  @override
  Future<void> stop() => _invoke<void>('stop').then((_) {});

  @override
  Future<bool> canGoBack() async => await _invoke<bool>('canGoBack') ?? false;

  @override
  Future<void> goBack() => _invoke<void>('goBack').then((_) {});

  @override
  Future<bool> canGoForward() async => await _invoke<bool>('canGoForward') ?? false;

  @override
  Future<void> goForward() => _invoke<void>('goForward').then((_) {});

  @override
  Future<String?> currentUrl() => _invoke<String>('currentUrl');

  @override
  Future<String?> title() => _invoke<String>('title');

  @override
  Future<void> applySettings() => _invoke<void>('applySettings').then((_) {});

  @override
  Future<Uint8List?> captureThumbnail() => _invoke<Uint8List>('capture');

  @override
  Future<bool> resolveDownload(String requestId, bool allow) async =>
      await _invoke<bool>('resolveDownload', {'id': requestId, 'allow': allow}) ?? false;

  // ---- DevTools ----
  @override
  Future<String?> evalJs(String js) async {
    final res = await _invoke<String>('evalJs', {'js': js});
    return res;
  }

  @override
  Future<String?> getHtml() async {
    final res = await _invoke<String>('getHtml');
    // WebView returns JSON-encoded string with quotes, unwrap
    if (res == null) return null;
    // Remove surrounding quotes if present
    if (res.startsWith('"') && res.endsWith('"')) {
      try {
        // crude unescape
        return res.substring(1, res.length - 1).replaceAll(r'\n', '\n').replaceAll(r'\"','"').replaceAll(r'\u003C','<');
      } catch (_) {
        return res;
      }
    }
    return res;
  }

  @override
  Future<List<Map<String, dynamic>>> getConsoleLogs() async {
    final r = await _invoke<List>('getConsole');
    if (r == null) return const [];
    return r.map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  @override
  Future<void> clearConsole() async {
    await _invoke<void>('clearConsole');
  }

  @override
  Future<void> injectEruda() async {
    await _invoke<void>('injectEruda');
  }

  @override
  Future<List<Map<String, dynamic>>> getNetworkLogs() async {
    final r = await _invoke<List>('getNetwork');
    if (r == null) return const [];
    return r.map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  @override
  Future<void> clearNetwork() async {
    await _invoke<void>('clearNetwork');
  }

  @override
  Future<String?> getCookies() async {
    return await _invoke<String>('getCookies');
  }

  @override
  Future<String?> getLocalStorage() async {
    return await _invoke<String>('getLocalStorage');
  }

  /// Ask native to destroy the retained page exactly once. Clear-data awaits
  /// this acknowledgement before requesting isolated-profile deletion.
  @override
  Future<void> releaseNativeOwnership() {
    if (tabId < 0 || !isAvailable) return Future<void>.value();
    return _nativeRelease ??= NativeBridge.guard(
      () => NativeBridge.releaseTab(tabId),
      false,
      'could not release tab $tabId',
    ).then<void>((_) {});
  }

  /// The tab is closed for good: let the native side destroy the page it has
  /// been holding. Disposing the widget alone must not do this — that is the
  /// whole point of retaining it.
  @override
  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _channel?.setMethodCallHandler(null);
    _channel = null;
    _pending.clear();
    unawaited(releaseNativeOwnership());
  }
}

class _PendingCall {
  final String method;
  final Map<String, dynamic>? arguments;
  const _PendingCall(this.method, this.arguments);
}

/// Shown where no Android WebView exists (widget tests, the design preview).
class _UnavailableSurface extends StatelessWidget {
  const _UnavailableSurface();

  @override
  Widget build(BuildContext context) {
    return Container(
      color: AppColors.bg,
      alignment: Alignment.center,
      padding: const EdgeInsets.all(28),
      child: Text(
        'Page rendering uses the Android System WebView.',
        textAlign: TextAlign.center,
        style: AppTheme.mono(size: 11.5, color: AppColors.textMuted, height: 1.6),
      ),
    );
  }
}
