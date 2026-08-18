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

  /// The tab this surface belongs to. The native side keeps one WebView per
  /// tab id and re-attaches it when the platform view is rebuilt, so leaving
  /// the browser and coming back shows the same page instead of a black
  /// surface with nothing loaded.
  final int tabId;

  MethodChannel? _channel;
  bool _disposed = false;

  /// Commands issued before the platform view exists are replayed on creation.
  final List<_PendingCall> _pending = [];

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
  ValueChanged<int>? onProgress;

  NativeWebViewEngine({this.tabId = -1, this.initialUrl = '', this.isPrivate = false});

  @override
  bool get isAvailable => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  // ------------------------------------------------------------------ view

  @override
  Widget buildView() {
    if (!isAvailable) return const _UnavailableSurface();
    return PlatformViewLink(
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
            'url': initialUrl,
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
    );
  }

  void _attach(int id) {
    if (_disposed) return;
    // A rebuilt platform view means a new channel name; drop the old handler
    // so a destroyed view cannot answer for this tab.
    _channel?.setMethodCallHandler(null);
    // Must match MrNobodyWebView's channel name: "mrnobody/webview_<viewId>".
    final channel = MethodChannel('${viewType}_$id');
    channel.setMethodCallHandler(_handleEvent);
    _channel = channel;
    for (final call in _pending) {
      channel.invokeMethod<void>(call.method, call.arguments).catchError(_report);
    }
    _pending.clear();
  }

  // ---------------------------------------------------------------- events

  Future<dynamic> _handleEvent(MethodCall call) async {
    final args = (call.arguments as Map?)?.cast<String, dynamic>() ?? const {};
    switch (call.method) {
      case 'onNavigation':
        final url = args['url'] as String?;
        if (url != null && url.isNotEmpty && url != 'about:blank') onUrlChanged?.call(url);
        final title = args['title'] as String?;
        if (title != null && title.isNotEmpty) onTitleChanged?.call(title);
        final loading = args['loading'] as bool?;
        if (loading != null) onLoadingChanged?.call(loading);
        break;
      case 'onTitle':
        final title = args['title'] as String?;
        if (title != null && title.isNotEmpty) onTitleChanged?.call(title);
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
      case 'onDownload':
        onDownload?.call(args['name'] as String?, args['error'] as String?);
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
      _pending.add(_PendingCall(method, arguments));
      return null;
    }
    try {
      return await channel.invokeMethod<T>(method, arguments);
    } catch (e) {
      _report(e);
      return null;
    }
  }

  void _report(Object error) => ErrorLog.instance.add('webview: $error');

  @override
  Future<void> loadUrl(String url) async {
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

  /// The tab is closed for good: let the native side destroy the page it has
  /// been holding. Disposing the widget alone must not do this — that is the
  /// whole point of retaining it.
  @override
  void dispose() {
    _disposed = true;
    _channel?.setMethodCallHandler(null);
    _channel = null;
    _pending.clear();
    if (tabId >= 0 && isAvailable) {
      NativeBridge.guard(
        () => NativeBridge.releaseTab(tabId),
        false,
        'could not release tab $tabId',
      );
    }
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
