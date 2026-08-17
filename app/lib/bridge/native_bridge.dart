import 'package:flutter/services.dart';

/// Bridge to the Java core (agent engine, tools, task store, filter engine)
/// via a MethodChannel on the Android host MainActivity.
class NativeBridge {
  static const MethodChannel _ch = MethodChannel('mrnobody/core');

  /// Run an instruction through the agent core (deterministic → search/extract).
  static Future<Map<String, dynamic>> runTask(String instruction) async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('runTask', {'instruction': instruction}));
  }

  /// Recent task list for the Tasks / Agent Home screens.
  static Future<List<Map<String, dynamic>>> recentTasks() async {
    final r = await _ch.invokeMethod('recentTasks');
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  /// Privacy counters for the dashboard.
  static Future<Map<String, dynamic>> privacyStats() async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('privacyStats'));
  }

  /// Whether history is enabled.
  static Future<bool> isHistoryEnabled() async {
    return await _ch.invokeMethod('isHistoryEnabled');
  }

  /// Persist the history on/off setting (privacy-first default is OFF).
  static Future<void> setHistoryEnabled(bool value) async {
    await _ch.invokeMethod('setHistoryEnabled', {'value': value});
  }

  /// First-launch flag (persisted by the core).
  static Future<bool> isFirstLaunchDone() async {
    return await _ch.invokeMethod('isFirstLaunchDone');
  }

  static Future<void> setFirstLaunchDone() async {
    await _ch.invokeMethod('setFirstLaunchDone');
  }

  /// Run a search through the core and return PARSED results (title/url/snippet)
  /// — never raw HTML. Used by the agent path, not the visible browser.
  static Future<Map<String, dynamic>> search(String query) async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('search', {'q': query}));
  }

  /// Download list from the system DownloadManager (name, size, status).
  static Future<List<Map<String, dynamic>>> downloads() async {
    final r = await _ch.invokeMethod('downloads');
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }
}
