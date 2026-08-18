import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/browser/native_webview_engine.dart';

/// A tab's page must outlive its widget, and die with the tab.
///
/// Flutter destroys a platform view the moment its widget leaves the tree, so
/// leaving the browser and coming back was destroying the WebView: the tab
/// returned as a black surface with no document loaded, and Reload did nothing
/// because there was nothing to reload. The native side now keeps one WebView
/// per tab id and only destroys it when Dart says the tab is closed.
///
/// That makes the release call load-bearing in both directions — forgetting it
/// leaks a WebView per closed tab, and calling it too eagerly brings the black
/// screen back — so it is pinned here.
void main() {
  final calls = <MethodCall>[];

  setUp(() {
    TestWidgetsFlutterBinding.ensureInitialized();
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('mrnobody/core'),
      (call) async {
        calls.add(call);
        return true;
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('mrnobody/core'), null);
  });

  test('closing a tab tells the core to destroy its retained page', () async {
    final engine = NativeWebViewEngine(tabId: 7, initialUrl: 'https://example.com');

    engine.dispose();
    await Future<void>.delayed(Duration.zero);

    expect(calls.map((c) => c.method), contains('releaseTab'));
    final release = calls.firstWhere((c) => c.method == 'releaseTab');
    expect((release.arguments as Map)['id'], 7,
        reason: 'the wrong tab id would destroy a page the user is still reading');
  });

  test('a surface with no tab identity releases nothing', () async {
    // tabId -1 means "this view owns its page" — the native side tears it down
    // itself, and a release call would refer to a tab that does not exist.
    final engine = NativeWebViewEngine(initialUrl: 'https://example.com');

    engine.dispose();
    await Future<void>.delayed(Duration.zero);

    expect(calls.where((c) => c.method == 'releaseTab'), isEmpty);
  });

  test('the tab id is handed to the platform view that adopts the page', () {
    final engine = NativeWebViewEngine(tabId: 3, initialUrl: 'https://example.com');
    // The id is what the native registry keys on; without it every rebuild
    // would create a fresh, empty WebView.
    expect(engine.tabId, 3);
    engine.dispose();
  });
}
