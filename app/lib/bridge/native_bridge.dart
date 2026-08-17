import 'package:flutter/services.dart';
import '../state/error_log.dart';

/// Bridge to the Java core (agent engine, tools, task store, filter engine)
/// via a MethodChannel on the Android host MainActivity.
///
/// Every call is a thin, typed wrapper: no business logic lives here, and the
/// core stays the owner of all persisted state.
class NativeBridge {
  static const MethodChannel _ch = MethodChannel('mrnobody/core');

  /// Run an instruction through the agent core (deterministic → search/extract).
  /// Returns the created task's id.
  static Future<Map<String, dynamic>> runTask(String instruction) async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('runTask', {'instruction': instruction}));
  }

  /// Recent task list for the Tasks / Agent Home screens.
  static Future<List<Map<String, dynamic>>> recentTasks() async {
    final r = await _ch.invokeMethod('recentTasks');
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  /// A single task by id (status, step, progress, result) for the detail screen.
  static Future<Map<String, dynamic>?> task(int id) async {
    final r = await _ch.invokeMethod('task', {'id': id});
    return r == null ? null : Map<String, dynamic>.from(r as Map);
  }

  /// Ask the core to stop a task. The request is persisted, so a worker in
  /// another process observes it at its next safe boundary.
  static Future<bool> cancelTask(int id) async {
    return await _ch.invokeMethod('cancelTask', {'id': id}) as bool? ?? false;
  }

  /// Privacy counters for the dashboard.
  static Future<Map<String, dynamic>> privacyStats() async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('privacyStats'));
  }

  /// All user settings the core owns, in one round trip.
  static Future<Map<String, dynamic>> getSettings() async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('getSettings'));
  }

  /// Persist one setting (history / js / suggestions / terminal / profile /
  /// searchEngine / provider).
  static Future<void> setSetting(String key, Object value) async {
    await _ch.invokeMethod('setSetting', {'key': key, 'value': value});
  }

  /// Whether history is enabled (kept for the privacy dashboard's single read).
  static Future<bool> isHistoryEnabled() async {
    return await _ch.invokeMethod('isHistoryEnabled') as bool;
  }

  /// Base URL / model / whether a key is stored for an AI provider. The key
  /// itself never leaves the core.
  static Future<Map<String, dynamic>> providerConfig(String id) async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('providerConfig', {'id': id}));
  }

  /// Save an AI provider's configuration, optionally making it the active one.
  static Future<void> saveProvider({
    required String id,
    String? key,
    String? base,
    String? model,
    bool active = false,
  }) async {
    await _ch.invokeMethod('saveProvider', {
      'id': id,
      'key': key,
      'base': base,
      'model': model,
      'active': active,
    });
  }

  /// First-launch flag (persisted by the core).
  static Future<bool> isFirstLaunchDone() async {
    return await _ch.invokeMethod('isFirstLaunchDone') as bool;
  }

  static Future<void> setFirstLaunchDone() async {
    await _ch.invokeMethod('setFirstLaunchDone');
  }

  /// Download list from the system DownloadManager (name, size, status).
  static Future<List<Map<String, dynamic>>> downloads() async {
    final r = await _ch.invokeMethod('downloads');
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  /// Locally stored bookmarks.
  static Future<List<Map<String, dynamic>>> bookmarks() async {
    final r = await _ch.invokeMethod('bookmarks');
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  static Future<void> addBookmark(String url, String title) async {
    await _ch.invokeMethod('addBookmark', {'url': url, 'title': title});
  }

  /// Delete the selected local data buckets: history, cookies, cache,
  /// sitedata, taskstate, workspace.
  static Future<Map<String, dynamic>> clearData(List<String> buckets) async {
    final r = await _ch.invokeMethod('clearData', {'buckets': buckets});
    return r == null ? <String, dynamic>{} : Map<String, dynamic>.from(r as Map);
  }

  /// Run [call], logging platform failures to the debug overlay and returning
  /// [fallback] instead of throwing — the UI must degrade, never crash.
  static Future<T> guard<T>(Future<T> Function() call, T fallback, String label) async {
    try {
      return await call();
    } catch (e) {
      ErrorLog.instance.add('$label: $e');
      return fallback;
    }
  }
}
