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

  /// Re-run an existing task in place — a "check again" follow-up. Returns true
  /// when the task was reset and re-enqueued.
  static Future<bool> rerunTask(int id) async {
    return await _ch.invokeMethod('rerunTask', {'id': id}) as bool? ?? false;
  }

  /// Reply inside an existing task. Same id, same chat — not a new thread.
  static Future<bool> followUpTask(int id, String text) async {
    return await _ch.invokeMethod('followUpTask', {'id': id, 'text': text}) as bool? ?? false;
  }

  /// Recent task list for the Tasks / Agent Home screens.
  static Future<List<Map<String, dynamic>>> recentTasks() async {
    final r = await _ch.invokeMethod('recentTasks');
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  /// Recurring checks that are actually scheduled, not just completed once.
  static Future<List<Map<String, dynamic>>> listMonitors() async {
    final r = await _ch.invokeMethod('listMonitors');
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  /// Hosts the user granted a session for. Cookie values never cross this channel.
  static Future<List<Map<String, dynamic>>> listAccounts() async {
    final r = await _ch.invokeMethod('listAccounts');
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  static Future<Map<String, dynamic>> importAccount(String host, String cookies) async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('importAccount', {
      'host': host,
      'cookies': cookies,
    }) as Map);
  }

  static Future<Map<String, dynamic>> captureAccount(String url) async {
    return Map<String, dynamic>.from(
        await _ch.invokeMethod('captureAccount', {'url': url}) as Map);
  }

  static Future<bool> revokeAccount(String host) async {
    return await _ch.invokeMethod('revokeAccount', {'host': host}) as bool? ?? false;
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

  /// Ask a provider which models the supplied key can actually use. Nothing is
  /// saved by this call, so the list can be fetched before committing.
  static Future<Map<String, dynamic>> listModels({
    required String id,
    String? base,
    String? key,
  }) async {
    final r = await _ch.invokeMethod('listModels', {'id': id, 'base': base, 'key': key});
    return Map<String, dynamic>.from(r as Map);
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

  /// Tell the native side a tab is closed for good, so the WebView it keeps
  /// alive for that tab can be destroyed. Not called when a tab is merely
  /// scrolled off screen or backgrounded — that page is deliberately retained.
  static Future<bool> releaseTab(int id) async {
    return await _ch.invokeMethod('releaseTab', {'id': id}) as bool? ?? false;
  }

  /// The append-only event log for one task: every tool call, its result or
  /// refusal, and each step change, with timings.
  ///
  /// This is what the chat transcript is built from. The sequence is
  /// contiguous per task, so a gap means an event was lost rather than that
  /// nothing happened — which is why the trace can be trusted to show the
  /// whole of what the agent did.
  static Future<List<Map<String, dynamic>>> taskEvents(int id) async {
    final r = await _ch.invokeMethod('taskEvents', {'id': id});
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  /// Which web engine is installed, and which privacy capabilities it actually
  /// supports on this device.
  ///
  /// Mr Nobody hosts the system WebView rather than bundling an engine, so
  /// multi-profile isolation, document-start scripts and proxy override are
  /// properties of the *device*, not of our build. A user on an outdated
  /// WebView gets genuinely weaker protection than one on a current WebView,
  /// and showing both the same UI is how "private tabs: isolated storage" came
  /// to be claimed when it was not true. This is the fact that lets the
  /// dashboard stop guessing.
  static Future<Map<String, dynamic>> engineInfo() async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('engineInfo'));
  }

  /// Download list from the app's own engine (name, size, status, folder).
  static Future<List<Map<String, dynamic>>> downloads() async {
    final r = await _ch.invokeMethod('downloads');
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  /// Open a finished download with whatever app handles its type.
  static Future<bool> openDownload(int id) async {
    return await _ch.invokeMethod('openDownload', {'id': id}) as bool? ?? false;
  }

  /// Stop a running download, keeping the bytes already written so it can be
  /// continued. Only possible because the app performs the transfer itself.
  static Future<bool> pauseDownload(int id) async {
    return await _ch.invokeMethod('pauseDownload', {'id': id}) as bool? ?? false;
  }

  /// Continue a paused, stalled or failed download from where it stopped.
  static Future<bool> resumeDownload(int id) async {
    return await _ch.invokeMethod('resumeDownload', {'id': id}) as bool? ?? false;
  }

  /// Stop a download for good and delete the partial file, keeping the row so
  /// the user can see what happened to it.
  static Future<bool> cancelDownload(int id) async {
    return await _ch.invokeMethod('cancelDownload', {'id': id}) as bool? ?? false;
  }

  /// Take a download off the list. Deletes the file too unless asked not to —
  /// clearing a row and destroying someone's film are different intentions.
  static Future<bool> removeDownload(int id, {bool deleteFile = true}) async {
    return await _ch.invokeMethod(
            'removeDownload', {'id': id, 'deleteFile': deleteFile}) as bool? ??
        false;
  }

  /// Where finished downloads are put: a label and whether it is a folder the
  /// user chose (rather than the system Downloads directory).
  static Future<Map<String, dynamic>> downloadFolder() async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('downloadFolder') as Map);
  }

  /// Open Android's folder picker and keep lasting access to what is chosen.
  /// Returns `{label, custom}`, or `{cancelled: true}`.
  static Future<Map<String, dynamic>> pickDownloadFolder() async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('pickDownloadFolder') as Map);
  }

  /// Go back to the system Downloads directory and release the folder grant.
  static Future<Map<String, dynamic>> clearDownloadFolder() async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('clearDownloadFolder') as Map);
  }

  /// The core's own error log — tool failures, AI errors, failed tasks. These
  /// never pass through a Dart try/catch, so the overlay has to ask for them.
  /// Apply a privacy mode (NORMAL / PRIVATE / NOBODY) and report what actually
  /// took effect — a refused mode must not look like it applied.
  /// Re-check a live Nobody session. Returns `{ok, problem, mode}`.
  static Future<Map<String, dynamic>> revalidateRoute() async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('revalidateRoute') as Map);
  }

  /// Allow or deny a WAITING task. Allow re-runs it; deny fails it.
  static Future<bool> resolveApproval(int id, {required bool allow}) async {
    return await _ch.invokeMethod('resolveApproval', {'id': id, 'allow': allow}) as bool? ?? false;
  }

  static Future<Map<String, dynamic>> applyPrivacyMode(String mode) async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('privacyMode', {'mode': mode}) as Map);
  }

  /// Configure the privacy route (Orbot Tor / HTTP proxy / direct) and re-apply
  /// the current mode so a live session picks up the change.
  static Future<Map<String, dynamic>> setProxy({
    String? kind,
    String? host,
    int? port,
    String? route,
  }) async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('setProxy', {
      'kind': kind,
      'host': host,
      'port': port,
      'route': route,
    }) as Map);
  }

  /// What the agent remembers: the on-device task history, newest first.
  static Future<Map<String, dynamic>> memoryInfo() async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('memoryInfo') as Map);
  }

  /// Erase everything the agent remembers (task history + event log).
  static Future<void> forgetMemory() async {
    await _ch.invokeMethod('forgetMemory');
  }

  /// The Phase 1 device benchmark: each subsystem reports pass/fail so a
  /// real-device run is a list the user reads off. Failures also land in the
  /// debug log so the ⓘ badge carries them.
  static Future<List<Map<String, dynamic>>> diagnostics() async {
    final r = await _ch.invokeMethod('diagnostics');
    return (r as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  static Future<List<String>> debugLog() async {
    final r = Map<String, dynamic>.from(await _ch.invokeMethod('debugLog') as Map);
    return (r['entries'] as List?)?.cast<String>() ?? const <String>[];
  }

  static Future<void> clearDebugLog() async {
    await _ch.invokeMethod('clearDebugLog');
  }

  /// What the connection can do right now: transport, metered, link speeds.
  static Future<Map<String, dynamic>> networkStatus() async {
    return Map<String, dynamic>.from(await _ch.invokeMethod('networkStatus') as Map);
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
      // An empty label means the caller is the logging path itself; recording
      // its failure would be a loop.
      if (label.isNotEmpty) ErrorLog.instance.add('$label: $e');
      return fallback;
    }
  }
}
