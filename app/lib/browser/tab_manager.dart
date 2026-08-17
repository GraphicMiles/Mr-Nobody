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
    tab.engine.dispose();
    if (_tabs.isEmpty) {
      _activeIndex = -1;
    } else if (wasActive) {
      _activeIndex = index > 0 ? index - 1 : 0; // sequential neighbor
    } else if (_activeIndex > index) {
      _activeIndex--;
    }
    notifyListeners();
  }

  void closeAll() {
    for (final t in _tabs) {
      t.engine.dispose();
    }
    _tabs.clear();
    _activeIndex = -1;
    notifyListeners();
  }
}
