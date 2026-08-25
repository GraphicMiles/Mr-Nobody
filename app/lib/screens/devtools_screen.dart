import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../browser/browser_tab.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/toast.dart';

/// In-app Developer Tools — like desktop browser DevTools but mobile-native.
/// Shows live console logs, network, page source, storage, and allows JS evaluation.
/// Also can inject Eruda (full mobile DevTools overlay) for visual inspection.

class DevToolsScreen extends StatefulWidget {
  final BrowserTab tab;

  const DevToolsScreen({super.key, required this.tab});

  @override
  State<DevToolsScreen> createState() => _DevToolsScreenState();
}

class _DevToolsScreenState extends State<DevToolsScreen> with SingleTickerProviderStateMixin {
  late TabController _tabController;
  final _jsInput = TextEditingController();
  final _consoleScroll = ScrollController();
  final _networkScroll = ScrollController();
  String _html = '';
  bool _loadingHtml = false;
  String _evalResult = '';
  String _cookies = '';
  String _localStorage = '';
  List<Map<String, dynamic>> _networkLogs = [];
  Timer? _networkPoll;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 5, vsync: this);
    widget.tab.consoleLogs.addListener(_onConsole);
    _loadNetwork();
    _networkPoll = Timer.periodic(const Duration(seconds: 2), (_) => _loadNetwork());
  }

  void _onConsole() {
    if (!mounted) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_consoleScroll.hasClients) {
        final max = _consoleScroll.position.maxScrollExtent;
        if (max - _consoleScroll.offset < 300) {
          _consoleScroll.animateTo(max, duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
        }
      }
    });
    setState(() {});
  }

  @override
  void dispose() {
    widget.tab.consoleLogs.removeListener(_onConsole);
    _tabController.dispose();
    _jsInput.dispose();
    _consoleScroll.dispose();
    _networkScroll.dispose();
    _networkPoll?.cancel();
    super.dispose();
  }

  Future<void> _loadHtml() async {
    setState(() => _loadingHtml = true);
    try {
      final html = await widget.tab.getHtml();
      if (!mounted) return;
      setState(() {
        _html = html ?? 'No HTML returned';
        _loadingHtml = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _html = 'Error loading HTML: $e';
        _loadingHtml = false;
      });
    }
  }

  Future<void> _loadNetwork() async {
    try {
      final logs = await widget.tab.getNetworkFromNative();
      if (!mounted) return;
      setState(() => _networkLogs = logs);
    } catch (_) {}
  }

  Future<void> _loadStorage() async {
    try {
      final cookies = await widget.tab.getCookies();
      final ls = await widget.tab.getLocalStorage();
      if (!mounted) return;
      setState(() {
        _cookies = cookies ?? '';
        _localStorage = ls ?? '';
      });
    } catch (e) {
      setState(() {
        _cookies = 'Error: $e';
      });
    }
  }

  Future<void> _evalJs() async {
    final js = _jsInput.text.trim();
    if (js.isEmpty) return;
    try {
      final result = await widget.tab.evalJs(js);
      if (!mounted) return;
      setState(() => _evalResult = result ?? 'null');
      AppToast.show(context, 'Evaluated');
    } catch (e) {
      if (!mounted) return;
      setState(() => _evalResult = 'Error: $e');
    }
  }

  Future<void> _injectEruda() async {
    try {
      await widget.tab.injectEruda();
      if (!mounted) return;
      AppToast.show(context, 'Eruda injected — check page for floating button');
    } catch (e) {
      AppToast.show(context, 'Failed to inject: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        title: Text('DevTools — ${widget.tab.host}',
            style: AppTheme.sans(size: 13, w: FontWeight.w600)),
        bottom: TabBar(
          controller: _tabController,
          isScrollable: true,
          labelColor: AppColors.accent,
          unselectedLabelColor: AppColors.textMuted,
          indicatorColor: AppColors.accent,
          labelStyle: AppTheme.mono(size: 11, w: FontWeight.w600),
          tabs: const [
            Tab(text: 'Console'),
            Tab(text: 'Network'),
            Tab(text: 'Elements'),
            Tab(text: 'Storage'),
            Tab(text: 'Eval'),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.bug_report, size: 18),
            tooltip: 'Inject Eruda (full DevTools overlay)',
            onPressed: _injectEruda,
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline, size: 18),
            tooltip: 'Clear',
            onPressed: () {
              widget.tab.clearConsole();
              widget.tab.clearNetwork();
              setState(() {
                _evalResult = '';
                _networkLogs = [];
              });
            },
          ),
        ],
      ),
      body: TabBarView(
        controller: _tabController,
        children: [
          _consoleTab(),
          _networkTab(),
          _elementsTab(),
          _storageTab(),
          _evalTab(),
        ],
      ),
    );
  }

  Widget _consoleTab() {
    return ValueListenableBuilder<List<Map<String, dynamic>>>(
      valueListenable: widget.tab.consoleLogs,
      builder: (context, logs, _) {
        if (logs.isEmpty) {
          return Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text('No console logs yet\nconsole.log, warn, error from the page will appear here\n\nTip: Use Eruda for richer inspection',
                  textAlign: TextAlign.center,
                  style: AppTheme.mono(size: 12, color: AppColors.textFaint, height: 1.6)),
            ),
          );
        }
        return ListView.builder(
          controller: _consoleScroll,
          padding: const EdgeInsets.all(12),
          itemCount: logs.length,
          itemBuilder: (context, i) {
            final entry = logs[i];
            final msg = entry['message'] as String? ?? '';
            final level = (entry['level'] as String? ?? 'LOG').toLowerCase();
            final source = entry['source'] as String? ?? '';
            final line = entry['line'] as int? ?? 0;
            Color color;
            IconData icon;
            switch (level) {
              case 'error':
                color = Colors.redAccent;
                icon = Icons.error_outline;
                break;
              case 'warning':
              case 'warn':
                color = Colors.amber;
                icon = Icons.warning_amber_rounded;
                break;
              case 'debug':
                color = AppColors.textFaint;
                icon = Icons.bug_report_outlined;
                break;
              default:
                color = AppColors.text;
                icon = Icons.info_outline;
            }
            return Container(
              margin: const EdgeInsets.only(bottom: 8),
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: AppColors.line),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(icon, size: 12, color: color),
                      const SizedBox(width: 6),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: color.withOpacity(0.15),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(level.toUpperCase(),
                            style: AppTheme.mono(size: 9, w: FontWeight.w700, color: color)),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(source.isEmpty ? '' : '${source.split('/').last}:$line',
                            style: AppTheme.mono(size: 9, color: AppColors.textFaint),
                            overflow: TextOverflow.ellipsis),
                      ),
                      GestureDetector(
                        onTap: () {
                          Clipboard.setData(ClipboardData(text: msg));
                          AppToast.show(context, 'Copied');
                        },
                        child: Icon(Icons.copy, size: 12, color: AppColors.textFaint),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  SelectableText(msg, style: AppTheme.mono(size: 11.5, color: color, height: 1.4)),
                ],
              ),
            );
          },
        );
      },
    );
  }

  Widget _networkTab() {
    if (_networkLogs.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.wifi, size: 24, color: AppColors.textFaint),
            const SizedBox(height: 8),
            Text('No network requests logged yet\nNavigate to see requests',
                textAlign: TextAlign.center,
                style: AppTheme.mono(size: 11, color: AppColors.textFaint)),
            const SizedBox(height: 12),
            ActionButton('Refresh', onTap: _loadNetwork, small: true),
          ],
        ),
      );
    }
    return ListView.builder(
      controller: _networkScroll,
      padding: const EdgeInsets.all(12),
      itemCount: _networkLogs.length,
      itemBuilder: (context, i) {
        final entry = _networkLogs.reversed.toList()[i];
        final url = entry['url'] as String? ?? '';
        final blocked = entry['blocked'] == true;
        final category = entry['category'] as String? ?? '';
        final mainFrame = entry['mainFrame'] == true;
        return Container(
          margin: const EdgeInsets.only(bottom: 6),
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: blocked ? Colors.red.withOpacity(0.08) : AppColors.surface,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: blocked ? Colors.red.withOpacity(0.3) : AppColors.line),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(mainFrame ? Icons.web : Icons.code, size: 12, color: AppColors.textFaint),
                  const SizedBox(width: 6),
                  if (blocked)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
                      decoration: BoxDecoration(color: Colors.red.withOpacity(0.2), borderRadius: BorderRadius.circular(4)),
                      child: Text('BLOCKED $category', style: AppTheme.mono(size: 8, w: FontWeight.w700, color: Colors.redAccent)),
                    )
                  else
                    Text(entry['method'] as String? ?? 'GET', style: AppTheme.mono(size: 9, w: FontWeight.w600, color: AppColors.textFaint)),
                  const Spacer(),
                  GestureDetector(
                    onTap: () {
                      Clipboard.setData(ClipboardData(text: url));
                      AppToast.show(context, 'URL copied');
                    },
                    child: Icon(Icons.copy, size: 10, color: AppColors.textFaint),
                  ),
                ],
              ),
              const SizedBox(height: 4),
              SelectableText(url, style: AppTheme.mono(size: 10, color: blocked ? Colors.redAccent : AppColors.textDim)),
            ],
          ),
        );
      },
    );
  }

  Widget _elementsTab() {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              Expanded(
                child: Text('Live DOM — outerHTML of current page\nUse Eruda for interactive editing',
                    style: AppTheme.sans(size: 11, color: AppColors.textMuted, height: 1.4)),
              ),
              const SizedBox(width: 8),
              ActionButton('Load HTML', onTap: _loadHtml, small: true),
            ],
          ),
        ),
        if (_loadingHtml) const LinearProgressIndicator(minHeight: 2, color: Color(0xFFFAFAFA)),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(12),
            child: SelectableText(
              _html.isEmpty ? 'Tap Load HTML to fetch document.documentElement.outerHTML\n\nTip: For live editing, inject Eruda and use its Elements panel' : _html,
              style: AppTheme.mono(size: 10, color: AppColors.textDim, height: 1.4),
            ),
          ),
        ),
      ],
    );
  }

  Widget _storageTab() {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Text('Storage & Cookies', style: AppTheme.sans(size: 13, w: FontWeight.w600)),
            const Spacer(),
            ActionButton('Load', onTap: _loadStorage, small: true),
          ],
        ),
        const SizedBox(height: 16),
        Text('Cookies:', style: AppTheme.mono(size: 11, w: FontWeight.w700, color: AppColors.textMuted)),
        const SizedBox(height: 6),
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(color: AppColors.surface, borderRadius: BorderRadius.circular(10), border: Border.all(color: AppColors.line)),
          child: SelectableText(_cookies.isEmpty ? 'No cookies loaded — tap Load' : _cookies,
              style: AppTheme.mono(size: 10.5, color: AppColors.textDim)),
        ),
        const SizedBox(height: 16),
        Text('LocalStorage:', style: AppTheme.mono(size: 11, w: FontWeight.w700, color: AppColors.textMuted)),
        const SizedBox(height: 6),
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(color: AppColors.surface, borderRadius: BorderRadius.circular(10), border: Border.all(color: AppColors.line)),
          child: SelectableText(_localStorage.isEmpty ? 'No localStorage — tap Load' : _localStorage,
              style: AppTheme.mono(size: 10.5, color: AppColors.textDim)),
        ),
      ],
    );
  }

  Widget _evalTab() {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: ListView(
        children: [
          Text('Evaluate JavaScript in page context',
              style: AppTheme.sans(size: 12, w: FontWeight.w600)),
          const SizedBox(height: 4),
          Text('Runs as the page — can access DOM, localStorage, etc. Be careful on sensitive pages.',
              style: AppTheme.sans(size: 11, color: AppColors.textFaint, height: 1.4)),
          const SizedBox(height: 12),
          Container(
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppColors.line),
            ),
            child: TextField(
              controller: _jsInput,
              maxLines: 8,
              minLines: 3,
              style: AppTheme.mono(size: 12, color: AppColors.text),
              decoration: InputDecoration(
                border: InputBorder.none,
                hintText: 'e.g.\ndocument.title\nlocalStorage.length\nwindow.location.href\n2+2\ndocument.querySelectorAll(\"a\").length\nJSON.stringify(performance.timing)',
                hintStyle: AppTheme.mono(size: 11, color: AppColors.textFaint, height: 1.4),
              ),
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(child: ActionButton('Run JS', solid: true, onTap: _evalJs)),
              const SizedBox(width: 12),
              ActionButton('Inject Eruda', onTap: _injectEruda),
            ],
          ),
          const SizedBox(height: 16),
          if (_evalResult.isNotEmpty)
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: AppColors.surface2,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.line),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text('Result:', style: AppTheme.mono(size: 10, w: FontWeight.w700, color: AppColors.textMuted)),
                      const Spacer(),
                      GestureDetector(
                        onTap: () {
                          Clipboard.setData(ClipboardData(text: _evalResult));
                          AppToast.show(context, 'Result copied');
                        },
                        child: Icon(Icons.copy, size: 12, color: AppColors.textFaint),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  SelectableText(_evalResult, style: AppTheme.mono(size: 11.5, color: AppColors.text)),
                ],
              ),
            ),
          const SizedBox(height: 24),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(color: AppColors.surface, borderRadius: BorderRadius.circular(10), border: Border.all(color: AppColors.line)),
            child: Text('Quick snippets:\n• document.documentElement.outerHTML.slice(0,5000)\n• performance.getEntriesByType(\"navigation\")[0].toJSON()\n• document.cookie\n• localStorage\n• window.eruda ? \"eruda ready\" : \"not injected\"',
                style: AppTheme.mono(size: 10.5, color: AppColors.textFaint, height: 1.5)),
          ),
        ],
      ),
    );
  }
}

class ActionButton extends StatelessWidget {
  final String label;
  final VoidCallback onTap;
  final bool solid;
  final bool small;

  const ActionButton(this.label, {super.key, required this.onTap, this.solid = false, this.small = false});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: EdgeInsets.symmetric(horizontal: small ? 12 : 16, vertical: small ? 6 : 10),
        decoration: BoxDecoration(
          color: solid ? const Color(0xFFFAFAFA) : const Color(0xFF1A1A1A),
          borderRadius: BorderRadius.circular(999),
          border: Border.all(color: solid ? const Color(0xFFFAFAFA) : const Color(0xFF2A2A2A)),
        ),
        child: Text(label,
            textAlign: TextAlign.center,
            style: TextStyle(
                fontSize: small ? 11 : 12.5,
                fontWeight: FontWeight.w600,
                color: solid ? Colors.black : Colors.white70,
                fontFamily: 'Inter')),
      ),
    );
  }
}
