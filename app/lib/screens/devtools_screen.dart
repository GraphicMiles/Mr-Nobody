import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../browser/browser_tab.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/toast.dart';

/// In-app Developer Tools — like desktop browser DevTools but mobile-native.
/// Shows live console logs, page source, and allows JS evaluation.
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
  String _html = '';
  bool _loadingHtml = false;
  String _evalResult = '';

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
    // Auto-scroll console when new logs arrive
    widget.tab.consoleLogs.addListener(_onConsole);
  }

  void _onConsole() {
    if (!mounted) return;
    // Auto-scroll near bottom
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
      AppToast.show(context, 'Eruda injected — check page overlay');
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
            style: AppTheme.sans(size: 14, w: FontWeight.w600)),
        bottom: TabBar(
          controller: _tabController,
          labelColor: AppColors.accent,
          unselectedLabelColor: AppColors.textMuted,
          indicatorColor: AppColors.accent,
          tabs: const [
            Tab(text: 'Console'),
            Tab(text: 'Elements'),
            Tab(text: 'Console Input'),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.bug_report, size: 18),
            tooltip: 'Inject Eruda (full DevTools)',
            onPressed: _injectEruda,
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline, size: 18),
            tooltip: 'Clear console',
            onPressed: () {
              widget.tab.clearConsole();
              setState(() => _evalResult = '');
            },
          ),
        ],
      ),
      body: TabBarView(
        controller: _tabController,
        children: [
          _consoleTab(),
          _elementsTab(),
          _inputTab(),
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
            child: Text('No console logs yet\nLogs from console.log, warn, error will appear here',
                textAlign: TextAlign.center,
                style: AppTheme.mono(size: 12, color: AppColors.textFaint, height: 1.5)),
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
            switch (level) {
              case 'error':
                color = Colors.redAccent;
                break;
              case 'warning':
              case 'warn':
                color = Colors.amber;
                break;
              case 'debug':
                color = AppColors.textFaint;
                break;
              default:
                color = AppColors.text;
            }
            return Container(
              margin: const EdgeInsets.only(bottom: 8),
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: AppColors.line),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
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
                        child: Text(source.isEmpty ? '' : '$source:$line',
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

  Widget _elementsTab() {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              Expanded(
                child: Text('Live DOM — tap Load to fetch outerHTML',
                    style: AppTheme.sans(size: 12, color: AppColors.textMuted)),
              ),
              ActionButton('Load', onTap: _loadHtml, small: true),
            ],
          ),
        ),
        if (_loadingHtml) const LinearProgressIndicator(minHeight: 2),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(12),
            child: SelectableText(
              _html.isEmpty ? 'No HTML loaded' : _html,
              style: AppTheme.mono(size: 10.5, color: AppColors.textDim, height: 1.4),
            ),
          ),
        ),
      ],
    );
  }

  Widget _inputTab() {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Evaluate JavaScript in page context',
              style: AppTheme.sans(size: 12, w: FontWeight.w600)),
          const SizedBox(height: 12),
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppColors.line),
            ),
            child: TextField(
              controller: _jsInput,
              maxLines: 6,
              minLines: 3,
              style: AppTheme.mono(size: 12, color: AppColors.text),
              decoration: InputDecoration(
                border: InputBorder.none,
                hintText: 'e.g. document.title\nlocalStorage.length\nwindow.location.href\n2+2',
                hintStyle: AppTheme.mono(size: 11, color: AppColors.textFaint),
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
                  Text('Result:', style: AppTheme.mono(size: 10, w: FontWeight.w700, color: AppColors.textMuted)),
                  const SizedBox(height: 6),
                  SelectableText(_evalResult, style: AppTheme.mono(size: 11.5, color: AppColors.text)),
                ],
              ),
            ),
          const SizedBox(height: 24),
          Text('Tips:\n• Use console.log in page — it appears in Console tab\n• Eruda gives full Elements/Network/Sources in-page\n• Eval runs as page — be careful with sensitive pages',
              style: AppTheme.sans(size: 11, color: AppColors.textFaint, height: 1.5)),
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
          color: solid ? AppColors.accent : AppColors.surface,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(color: solid ? AppColors.accent : AppColors.lineStrong),
        ),
        child: Text(label,
            textAlign: TextAlign.center,
            style: AppTheme.sans(
                size: small ? 11 : 12.5,
                w: FontWeight.w600,
                color: solid ? AppColors.accentInk : AppColors.text)),
      ),
    );
  }
}
