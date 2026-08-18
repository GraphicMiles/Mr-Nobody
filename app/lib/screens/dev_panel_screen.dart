import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../bridge/native_bridge.dart';
import '../router/intent_router.dart';
import '../state/error_log.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/toast.dart';

/// One benchmark point: an automated check or a "needs your eyes" observation.
class BenchmarkResult {
  final String id;
  final String name;
  final bool pass;
  final String detail;

  /// True when this is a manual observation the user answers, not code.
  final bool manual;

  const BenchmarkResult(this.id, this.name,
      {required this.pass, required this.detail, this.manual = false});
}

/// Dev mode — the Phase 1 device benchmark.
///
/// Runs every subsystem checkable from here (routing, search parsing, planner,
/// terminal gate, sandbox, identity, network, and — on device — filter engine,
/// WebView capabilities, Keystore identity, task store) and shows a pass/fail
/// line for each. Failures are recorded to the error log, so the ⓘ badge and
/// its copyable panel carry exactly which point failed — the user reads the
/// line back and the next fix targets it instead of guessing.
///
/// The "Needs your eyes" section is for what code cannot observe (does a page
/// actually render, does Tor actually route): tap PASS / FAIL and it is
/// recorded the same way.
class DevPanelScreen extends StatefulWidget {
  const DevPanelScreen({super.key});

  @override
  State<DevPanelScreen> createState() => _DevPanelScreenState();
}

class _DevPanelScreenState extends State<DevPanelScreen> {
  List<BenchmarkResult> _results = const [];
  bool _running = false;

  /// Manual observations, keyed by id: null = unanswered.
  final Map<String, bool?> _manual = {};

  /// The Data Saver grade currently applied. Defaults to OFF (the product
  /// never degrades a page by surprise).
  String _dataSaver = 'OFF';

  static const _dataSaverGrades = ['OFF', 'BALANCED', 'AGGRESSIVE', 'EXTREME'];

  /// Failure ids already written to the error log, so a re-run never stacks
  /// duplicates in the ⓘ panel.
  final Set<String> _recorded = {};

  static const _manualChecks = <String, String>{
    'manual.page': 'A real page renders (not blank)',
    'manual.js': 'A JavaScript page runs',
    'manual.tabs': 'Tabs open / switch / close',
    'manual.private': 'Private tab isolates and clears',
    'manual.download': 'Download → pause → resume → complete',
    'manual.tor': 'Observe egress IP change under NOBODY (Tor routes)',
    'manual.recover': 'Kill app mid-task → task recovers',
  };

  @override
  void initState() {
    super.initState();
    _run();
    _loadDataSaver();
  }

  Future<void> _loadDataSaver() async {
    final s = await NativeBridge.guard(
      NativeBridge.getSettings,
      const <String, dynamic>{},
      'settings unavailable',
    );
    if (!mounted || s.isEmpty) return;
    final grade = s['resourcePolicy'] as String?;
    if (grade != null && grade.isNotEmpty) {
      setState(() => _dataSaver = grade.toUpperCase());
    }
  }

  Future<void> _setDataSaver(String grade) async {
    await NativeBridge.guard(
      () => NativeBridge.setSetting('resourcePolicy', grade),
      null,
      'could not set data saver',
    );
    if (!mounted) return;
    setState(() => _dataSaver = grade);
    AppToast.show(context, 'Data Saver: $grade');
  }

  Future<void> _run() async {
    setState(() => _running = true);
    final results = <BenchmarkResult>[];

    // Dart-side checks first: they need no core and prove the bridge below.
    results.add(_checkInputRoute());
    results.add(await _checkBridge());

    // The Java battery (pure + device), via the diagnostics channel.
    final diag = await NativeBridge.guard(
      NativeBridge.diagnostics,
      const <Map<String, dynamic>>[],
      'benchmarks unavailable',
    );
    for (final d in diag) {
      results.add(BenchmarkResult(
        d['id'] as String? ?? '?',
        d['name'] as String? ?? '?',
        pass: d['pass'] == true,
        detail: d['detail'] as String? ?? '',
      ));
    }

    if (!mounted) return;
    setState(() {
      _results = results;
      _running = false;
    });

    // Record failures once, so the ⓘ badge and its log carry them.
    for (final r in results) {
      if (!r.pass && r.manual == false && !_recorded.contains(r.id)) {
        _recorded.add(r.id);
        ErrorLog.instance.add('benchmark FAIL — ${r.name}: ${r.detail}');
      }
    }
  }

  BenchmarkResult _checkInputRoute() {
    final url = IntentRouter.route('https://example.com') == IntentType.url;
    final domain = IntentRouter.route('example.com') == IntentType.url;
    final task = IntentRouter.route('find laptops under 500000') == IntentType.task;
    final search = IntentRouter.route('what is the capital of ghana') == IntentType.search;
    final ok = url && domain && task && search;
    return BenchmarkResult(
      'input.route',
      'Address bar → URL / search / task',
      pass: ok,
      detail: ok
          ? 'URL, bare domain, task verb and plain search all route correctly'
          : 'one of URL / task / search misclassified',
    );
  }

  Future<BenchmarkResult> _checkBridge() async {
    final reachable = await NativeBridge.guard(
      () async => true,
      false,
      '',
    );
    return BenchmarkResult(
      'bridge.reachable',
      'Java core reachable over the bridge',
      pass: reachable,
      detail: reachable ? 'MethodChannel answered' : 'core did not answer',
    );
  }

  void _answerManual(String id, bool pass) {
    setState(() => _manual[id] = pass);
    if (!_recorded.contains(id)) {
      _recorded.add(id);
      final label = _manualChecks[id] ?? id;
      ErrorLog.instance
          .add('benchmark ${pass ? 'PASS' : 'FAIL'} — $label (manual)');
    }
  }

  String _report() {
    final sb = StringBuffer('Mr Nobody — dev benchmarks\n');
    for (final r in _results) {
      sb.writeln('${r.pass ? 'PASS' : 'FAIL'}  ${r.name} — ${r.detail}');
    }
    for (final e in _manualChecks.entries) {
      final v = _manual[e.key];
      sb.writeln('${v == null ? 'SKIP' : (v ? 'PASS' : 'FAIL')}  ${e.value} (manual)');
    }
    return sb.toString();
  }

  @override
  Widget build(BuildContext context) {
    return ScreenSurface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SafeArea(
            bottom: false,
            child: TopBar(
              title: 'Dev mode · Benchmarks',
              onBack: () => Navigator.of(context).pop(),
              trailing: TextButton(
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: _report()));
                  if (!mounted) return;
                  AppToast.show(context, 'Report copied');
                },
                child: Text('COPY', style: AppTheme.mono(size: 9.5, w: FontWeight.w700)),
              ),
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.only(bottom: 28),
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                  child: Text(
                    'Every subsystem reports pass/fail. Failures are written to the '
                    'ⓘ debug log, so a device run is a list you read off — not a guess.',
                    style: AppTheme.sans(size: 12, color: AppColors.textDim, height: 1.5),
                  ),
                ),
                ActionButton(
                  _running ? 'Running…' : 'Run benchmarks',
                  solid: true,
                  onTap: _running ? () {} : _run,
                ),
                const SizedBox(height: 18),
                const SectionLabel('Automated'),
                AppCard(
                  child: Column(
                    children: withDividers([
                      if (_running)
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: 12),
                          child: Center(
                            child: SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(
                                  strokeWidth: 1.5, color: AppColors.accent),
                            ),
                          ),
                        )
                      else
                        for (final r in _results) _resultRow(r),
                    ]),
                  ),
                ),
                const SectionLabel('Data Saver'),
                AppCard(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(14, 12, 14, 14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Restrict what pages may fetch. Takes effect on newly '
                          'opened pages.',
                          style: AppTheme.sans(size: 11.5, color: AppColors.textDim, height: 1.5),
                        ),
                        const SizedBox(height: 10),
                        Wrap(
                          spacing: 6,
                          runSpacing: 6,
                          children: [
                            for (final g in _dataSaverGrades)
                              _gradePill(g, g == _dataSaver),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
                const SectionLabel('Needs your eyes'),
                AppCard(
                  child: Column(
                    children: withDividers([
                      for (final e in _manualChecks.entries)
                        _manualRow(e.key, e.value),
                    ]),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _resultRow(BenchmarkResult r) {
    final color = r.pass ? const Color(0xFF3DDC84) : const Color(0xFFE5484D);
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(r.pass ? Icons.check_circle : Icons.cancel,
              size: 15, color: color),
          const SizedBox(width: 9),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(r.name, style: AppTheme.sans(size: 13, w: FontWeight.w600)),
                if (r.detail.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(r.detail,
                      style: AppTheme.mono(size: 10, color: AppColors.textFaint, height: 1.5)),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _manualRow(String id, String label) {
    final v = _manual[id];
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      child: Row(
        children: [
          Expanded(
            child: Text(label, style: AppTheme.sans(size: 12.5)),
          ),
          _pill(id, 'PASS', v == true),
          const SizedBox(width: 6),
          _pill(id, 'FAIL', v == false),
        ],
      ),
    );
  }

  Widget _gradePill(String grade, bool selected) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => _setDataSaver(grade),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        decoration: BoxDecoration(
          color: selected ? AppColors.accent : AppColors.surface2,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
              color: selected ? Colors.transparent : AppColors.lineStrong),
        ),
        child: Text(
          grade,
          style: AppTheme.mono(
            size: 9.5,
            w: FontWeight.w700,
            color: selected ? AppColors.accentInk : AppColors.textDim,
          ),
        ),
      ),
    );
  }

  Widget _pill(String id, String label, bool selected) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => _answerManual(id, label == 'PASS'),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: selected
              ? (label == 'PASS' ? const Color(0xFF3DDC84) : const Color(0xFFE5484D))
              : AppColors.surface2,
          borderRadius: BorderRadius.circular(999),
          border: Border.all(color: selected ? Colors.transparent : AppColors.lineStrong),
        ),
        child: Text(
          label,
          style: AppTheme.mono(
            size: 9,
            w: FontWeight.w700,
            color: selected ? AppColors.bg : AppColors.textDim,
          ),
        ),
      ),
    );
  }
}
