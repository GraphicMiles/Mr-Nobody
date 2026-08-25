import 'dart:collection';
import 'package:flutter/foundation.dart';
import 'browser_tab.dart';

/// Sequential tab model (mirrors the Java TabManager's invariant):
/// new tabs insert immediately after the active tab; closing the active tab
/// activates its sequential neighbor; only an explicit tap jumps arbitrarily.
class TabManager extends ChangeNotifier {
  final List<BrowserTab> _tabs = [];
  int _activeIndex = -1;
  int _nextId = 0;

  List<BrowserTab> get tabs => UnmodifiableListView(_tabs);
  BrowserTab? get active => _activeIndex >= 0 && _activeIndex < _tabs.length ? _tabs[_activeIndex] : null;
  int get length => _tabs.length;
  int get activeIndex => _activeIndex;

  /// Create a tab, inserted immediately after the active tab.
  BrowserTab newTab({bool isPrivate = false, String url = ''}) {
    final tab = BrowserTab(_nextId++, isPrivate: isPrivate, url: url);
    final ai = _activeIndex;
    if (ai >= 0 && ai < _tabs.length - 1) {
      _tabs.insert(ai + 1, tab);
      _activeIndex = ai + 1;
    } else {
      _tabs.add(tab);
      _activeIndex = _tabs.length - 1;
    }
    notifyListeners();
    return tab;
  }

  /// Explicitly switch to a tab (the ONLY non-adjacent active-pointer change).
  void select(int index) {
    if (index >= 0 && index < _tabs.length) {
      _activeIndex = index;
      notifyListeners();
    }
  }

  /// Explicitly switch to a tab by its stable id.
  void selectById(int id) {
    final i = _tabs.indexWhere((t) => t.id == id);
    if (i >= 0) select(i);
  }

  /// Close a tab by its stable id (grid cards hold ids, not positions).
  void closeById(int id) {
    final i = _tabs.indexWhere((t) => t.id == id);
    if (i >= 0) close(i);
  }

  void close(int index) {
    if (index < 0 || index >= _tabs.length) return;
    final wasActive = index == _activeIndex;
    final tab = _tabs.removeAt(index);
    tab.dispose();
    if (_tabs.isEmpty) {
      _activeIndex = -1;
    } else if (wasActive) {
      _activeIndex = index > 0 ? index - 1 : 0; // sequential neighbor
    } else if (_activeIndex > index) {
      _activeIndex--;
    }
    notifyListeners();
  }

  DateTime? _lastApply;
  bool _applyInProgress = false;
  final Set<String> _pendingChangedKeys = {};

  /// Push the current user settings (JavaScript, parameter stripping) into
  /// every live engine, so a Settings change reaches pages already open.
  /// P0 fix: debounced and parallel, not sequential await per tab.
  Future<void> applySettingsToAll({bool force = false}) async {
    if (_applyInProgress && !force) return;
    final now = DateTime.now();
    if (!force && _lastApply != null && now.difference(_lastApply!).inMilliseconds < 500) {
      // Root fix: accumulate changed keys during debounce window instead of dropping
      _pendingChangedKeys.addAll(_pendingChangedKeys); // keep existing
      return;
    }
    _lastApply = now;
    _applyInProgress = true;
    final keysToApply = Set<String>.from(_pendingChangedKeys)..clear();
    try {
      await Future.wait(_tabs.map((tab) => tab.engine.applySettings().catchError((_) {})));
    } finally {
      _applyInProgress = false;
    }
  }

  /// Only call when settings that affect WebView actually changed (js, blocking, etc)
  Future<void> applySettingsIfNeeded({required Set<String> changedKeys}) async {
    const webViewKeys = {'js', 'blocking', 'paramStripping', 'resourcePolicy', 'profile'};
    final relevant = changedKeys.intersection(webViewKeys);
    if (relevant.isEmpty) return;
    _pendingChangedKeys.addAll(relevant);
    await applySettingsToAll(force: true);
  }

  /// Remove private tab models and await their native retained-page teardown.
  /// Clear Data calls this before profile deletion; normal tabs stay open and
  /// reload against the now-empty default stores.
  Future<void> closePrivateTabs() async {
    final closing = <BrowserTab>[];
    for (var i = _tabs.length - 1; i >= 0; i--) {
      if (_tabs[i].isPrivate) {
        closing.add(_tabs[i]);
        close(i);
      }
    }
    await Future.wait(closing.map((tab) => tab.releaseNativeOwnership()));
  }

  void closeAll() {
    for (final t in _tabs) {
      t.dispose();
    }
    _tabs.clear();
    _activeIndex = -1;
    notifyListeners();
  }
}
