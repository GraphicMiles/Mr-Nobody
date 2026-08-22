import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:mrnobody/screens/settings_screen.dart';
import 'package:mrnobody/state/app_state.dart';

import 'test_fonts.dart';

/// The quiet update check and the Settings → App → Updates surface.
///
/// The channel mocks the exact maps the Java core returns, so this pins the
/// whole visible contract: badge appears only for a newer, non-dismissed
/// version; "remind me later" suppresses that version without a network
/// round trip; the sheet shows notes + integrity data + the two choices.
void main() {
  setUpAll(loadTestFonts);

  Map<String, Object> updateMap({
    required String latest,
    required bool available,
    required bool dismissed,
    bool networkFailed = false,
    bool required = false,
    int checkedAt = 1_753_200_000_000,
  }) =>
      <String, Object>{
        'installedVersion': '1.0.0',
        'latestVersion': latest,
        'updateAvailable': available,
        'required': required && available,
        'releaseNotes': 'Faster tabs and reliable previews.',
        'downloadUrl': 'https://cdn.example.com/mr-nobody-$latest.apk',
        'sha256': 'b1c6939aa1650b3fd69711713c522336f9fc10a84ac8fffc8f7e0957743f5d7c',
        'signature': '',
        'publishedAt': '2026-08-22T00:00:00Z',
        'lastCheckedAt': checkedAt,
        'source': 'cache',
        'dismissed': dismissed,
        'networkFailed': networkFailed,
      };

  void mockCore({
    required Map<String, Object> cached,
    required Map<String, Object> checked,
    required Map<String, Object> dismissedMap,
  }) {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('mrnobody/core'),
            (call) async {
      switch (call.method) {
        case 'getSettings':
          return <Object?, Object?>{
            'history': false,
            'js': true,
            'suggestions': false,
            'terminal': false,
            'blocking': true,
            'paramStripping': true,
            'profile': 'BALANCED',
            'provider': 'local',
            'privacyMode': 'NORMAL',
            'searchEngine': 'https://duckduckgo.com/?q=',
            'approvalMode': 'CAUTIOUS',
            'resourcePolicy': 'OFF',
            'theme': 'classic',
          };
        case 'updateStatus':
          return cached;
        case 'updateCheck':
          return checked;
        case 'updateDismiss':
          return dismissedMap;
        default:
          return null;
      }
    });
  }

  testWidgets('badge, details and dismiss flow', (tester) async {
    final dismissedFor = <String>[];
    mockCore(
      cached: updateMap(latest: '', available: false, dismissed: false),
      checked: updateMap(latest: '1.1.0', available: true, dismissed: false),
      dismissedMap: updateMap(latest: '1.1.0', available: true, dismissed: true),
    );

    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    // Settings load resolves, then the quiet kickoff check resolves and
    // publishes the badge.
    await tester.pump();
    await tester.pump();
    await tester.pump();
    await tester.pump();

    expect(find.text('Updates'), findsOneWidget);
    expect(find.text('v1.1.0'), findsOneWidget,
        reason: 'a newer published version must show in the row');

    await tester.tap(find.text('Updates'));
    await tester.pumpAndSettle();

    // The sheet: version, optional chip, notes, integrity, the two choices.
    expect(find.text('Faster tabs and reliable previews.'), findsOneWidget);
    expect(find.text('Optional'.toUpperCase()), findsOneWidget);
    expect(find.textContaining('sha256'), findsOneWidget);
    expect(find.text('Update'), findsOneWidget);
    expect(find.text('Remind me later'), findsOneWidget);

    // The dismiss path: the core is asked about exactly that version, the
    // sheet closes, and the badge goes away.
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('mrnobody/core'),
            (call) async {
      if (call.method == 'updateDismiss') {
        final args = call.arguments as Map;
        dismissedFor.add(args['version'] as String);
        return updateMap(latest: '1.1.0', available: true, dismissed: true);
      }
      return null;
    });

    await tester.tap(find.text('Remind me later'));
    await tester.pumpAndSettle();

    expect(dismissedFor, ['1.1.0']);
    expect(find.text('Remind me later'), findsNothing,
        reason: 'the sheet must close after dismissing');
    expect(find.text('Up to date'), findsOneWidget,
        reason: 'a dismissed release no longer badges');
  });

  testWidgets('up-to-date state shows no badge and offers a recheck',
      (tester) async {
    // Direct state: a check completed and nothing newer exists.
    AppState.instance.updates = UpdateStatus(
      installedVersion: '1.0.0',
      latestVersion: '1.0.0',
      lastCheckedAt: DateTime.now().millisecondsSinceEpoch,
      source: 'cache',
    );
    mockCore(
      cached:
          updateMap(latest: '1.0.0', available: false, dismissed: false),
      checked:
          updateMap(latest: '1.0.0', available: false, dismissed: false),
      dismissedMap:
          updateMap(latest: '1.0.0', available: false, dismissed: false),
    );

    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pump();
    await tester.pump();

    expect(find.text('Updates'), findsOneWidget);
    expect(find.text('Up to date'), findsOneWidget);
    expect(find.text('v1.0.0'), findsNothing,
        reason: 'the same version is not an update');

    await tester.tap(find.text('Updates'));
    await tester.pumpAndSettle();

    expect(find.textContaining('up to date'), findsOneWidget);
    expect(find.text('Check again'), findsOneWidget);
    expect(find.text('Remind me later'), findsNothing);
  });

  testWidgets('never-checked state offers a check instead of guessing',
      (tester) async {
    AppState.instance.updates = const UpdateStatus(networkFailed: true);
    mockCore(
      cached: updateMap(latest: '', available: false, dismissed: false),
      checked:
          updateMap(latest: '1.1.0', available: true, dismissed: false),
      dismissedMap:
          updateMap(latest: '1.1.0', available: true, dismissed: false),
    );

    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    await tester.pump();
    await tester.pump();

    expect(find.text('—'), findsOneWidget,
        reason: 'no check has completed, so no version is claimed');

    await tester.tap(find.text('Updates'));
    await tester.pumpAndSettle();

    expect(find.text('Check for updates'), findsOneWidget);

    await tester.tap(find.text('Check for updates'));
    await tester.pumpAndSettle();

    // The manual check answered: the sheet now shows the release.
    expect(find.text('Optional'.toUpperCase()), findsOneWidget);
    expect(find.text('Faster tabs and reliable previews.'), findsOneWidget);
  });
}
