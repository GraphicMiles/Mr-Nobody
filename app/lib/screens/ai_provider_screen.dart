import 'package:flutter/material.dart';
import '../bridge/native_bridge.dart';
import '../state/app_state.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/debug_fab.dart';
import '../widgets/toast.dart';

/// AI provider — "choose where the brain runs" (`#v-ai`).
///
/// Local keeps everything on-device. A remote provider is opt-in, needs a key
/// the user supplies, and is only made active after an explicit Save — the
/// disclosure at the bottom is part of the contract (V1 §11, V2 §19).
///
/// Models are **fetched from the provider**, never hardcoded. A model id is the
/// most perishable thing in this system: Groq retired
/// `llama-3.3-70b-versatile` and every install carrying it started answering
/// "model_not_found", which reads like a bug in the app.
class AiProviderScreen extends StatefulWidget {
  final String? initialProvider;

  const AiProviderScreen({super.key, this.initialProvider});

  @override
  State<AiProviderScreen> createState() => _AiProviderScreenState();
}

class _AiProviderScreenState extends State<AiProviderScreen> {
  final _state = AppState.instance;
  final _key = TextEditingController();
  final _base = TextEditingController();
  final _manualModel = TextEditingController();

  late String _selected = widget.initialProvider ?? _state.providerId;
  bool _hasStoredKey = false;
  String _model = '';
  List<String> _models = const [];
  bool _loadingModels = false;
  String? _modelError;
  bool _manualEntry = false;

  @override
  void initState() {
    super.initState();
    _loadConfig();
  }

  @override
  void dispose() {
    _key.dispose();
    _base.dispose();
    _manualModel.dispose();
    super.dispose();
  }

  Future<void> _loadConfig() async {
    final cfg = await NativeBridge.guard(
      () => NativeBridge.providerConfig(_selected),
      const <String, dynamic>{},
      'provider config unavailable',
    );
    if (!mounted) return;
    setState(() {
      _base.text = cfg['base'] as String? ?? '';
      _model = cfg['model'] as String? ?? '';
      _manualModel.text = _model;
      _hasStoredKey = cfg['hasKey'] as bool? ?? false;
      _key.text = '';
      _models = const [];
      _modelError = null;
      _manualEntry = false;
    });
  }

  bool get _isLocal => _selected == 'local';

  Future<void> _refreshModels() async {
    setState(() {
      _loadingModels = true;
      _modelError = null;
    });
    final response = await NativeBridge.guard(
      () => NativeBridge.listModels(
        id: _selected,
        base: _base.text.trim(),
        key: _key.text.trim(),
      ),
      const <String, dynamic>{},
      'could not list models',
    );
    if (!mounted) return;
    final models = (response['models'] as List?)?.cast<String>() ?? const <String>[];
    setState(() {
      _loadingModels = false;
      _models = models;
      _modelError = response['error'] as String?;
      // A model that no longer exists must not stay selected.
      if (_model.isNotEmpty && models.isNotEmpty && !models.contains(_model)) {
        _modelError ??= '"$_model" is no longer offered — pick another.';
        _model = '';
      }
    });
  }

  Future<void> _save() async {
    if (_isLocal) {
      await _state.setProvider('local');
      if (!mounted) return;
      AppToast.show(context, 'Local provider active');
      Navigator.of(context).pop();
      return;
    }
    if (_base.text.trim().isEmpty) {
      AppToast.show(context, 'This provider needs a base URL');
      return;
    }
    if (_key.text.trim().isEmpty && !_hasStoredKey) {
      AppToast.show(context, 'Add an API key first');
      return;
    }
    final model = _manualEntry ? _manualModel.text.trim() : _model;
    if (model.isEmpty) {
      AppToast.show(context, 'Choose a model — tap Refresh to list them');
      return;
    }
    await NativeBridge.guard(
      () => NativeBridge.saveProvider(
        id: _selected,
        key: _key.text.trim().isEmpty ? null : _key.text.trim(),
        base: _base.text.trim(),
        model: model,
        active: true,
      ),
      null,
      'could not save provider',
    );
    await _state.load();
    if (!mounted) return;
    AppToast.show(context, '${AiProviderOption.byId(_selected).shortName} · $model');
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      resizeToAvoidBottomInset: true,
      body: PanelShell(
        title: 'AI provider',
        onBack: () => Navigator.of(context).pop(),
        overlay: const DebugOverlay(bottomInset: 20),
        children: [
          const SectionLabel('Choose where the brain runs'),
          AppCard(
            child: Column(
              children: withDividers([
                for (final p in AiProviderOption.all) _providerRow(p),
              ]),
            ),
          ),
          if (!_isLocal) ...[
            const SectionLabel('Connection'),
            AppCard(
              child: Column(
                children: [
                  _field('API KEY', _key,
                      obscure: true,
                      hint: _hasStoredKey ? 'saved — type to replace' : 'paste your key'),
                  _field('BASE URL', _base, hint: 'https://…/v1'),
                  const SizedBox(height: 13),
                ],
              ),
            ),
            _modelsSection(),
          ],
          Padding(
            padding: const EdgeInsets.all(16),
            child: ActionButton('Save', solid: true, onTap: _save),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
            child: Text(
              _isLocal
                  ? 'Local keeps every request on this device. Basic browsing never needs an AI provider.'
                  : 'If a remote provider is enabled, task context may leave the device. '
                      'Models are read from your account — nothing is assumed about what your key can use.',
              style: AppTheme.sans(size: 11, color: AppColors.textMuted, height: 1.5),
            ),
          ),
        ],
      ),
    );
  }

  Widget _modelsSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 20, 16, 10),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  'MODEL',
                  style: AppTheme.mono(
                    size: 10.5,
                    color: AppColors.textMuted,
                    w: FontWeight.w600,
                    letterSpacing: 1.26,
                  ),
                ),
              ),
              GestureDetector(
                onTap: _loadingModels ? null : _refreshModels,
                behavior: HitTestBehavior.opaque,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  decoration: BoxDecoration(
                    color: AppColors.surface2,
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(color: AppColors.lineStrong),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (_loadingModels)
                        const SizedBox(
                          width: 10,
                          height: 10,
                          child: CircularProgressIndicator(strokeWidth: 1.5, color: AppColors.accent),
                        )
                      else
                        const Icon(Icons.refresh, size: 12, color: AppColors.accent),
                      const SizedBox(width: 6),
                      Text('Refresh',
                          style: AppTheme.mono(size: 9.5, w: FontWeight.w700, color: AppColors.accent)),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
        AppCard(
          child: Column(
            children: [
              if (_modelError != null)
                Padding(
                  padding: const EdgeInsets.fromLTRB(14, 13, 14, 3),
                  child: Text(
                    _modelError!,
                    style: AppTheme.sans(size: 11.5, color: AppColors.textDim, height: 1.45),
                  ),
                ),
              if (_manualEntry)
                Column(
                  children: [
                    _field('MODEL ID', _manualModel, hint: 'exact id from your provider'),
                    const SizedBox(height: 13),
                  ],
                )
              else if (_models.isEmpty)
                EmptyNote(_loadingModels
                    ? 'Asking the provider…'
                    : 'Tap Refresh to list the models your key can use.')
              else
                Column(children: withDividers([for (final m in _models) _modelRow(m)])),
              const RowDivider(),
              SettingRow(
                label: _manualEntry ? 'Pick from the list instead' : 'Enter a model id manually',
                onTap: () => setState(() => _manualEntry = !_manualEntry),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _modelRow(String id) {
    final selected = id == _model;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () => setState(() => _model = id),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        color: selected ? AppColors.surface2 : Colors.transparent,
        child: Row(
          children: [
            Icon(
              selected ? Icons.radio_button_checked : Icons.radio_button_unchecked,
              size: 15,
              color: selected ? AppColors.accent : AppColors.textMuted,
            ),
            const SizedBox(width: 11),
            Expanded(
              child: Text(id,
                  style: AppTheme.mono(
                    size: 11.5,
                    color: selected ? AppColors.text : AppColors.textDim,
                    w: selected ? FontWeight.w600 : FontWeight.w500,
                  )),
            ),
          ],
        ),
      ),
    );
  }

  Widget _providerRow(AiProviderOption p) {
    final selected = p.id == _selected;
    final active = p.id == _state.providerId;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () {
        setState(() => _selected = p.id);
        _loadConfig();
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        decoration: BoxDecoration(
          color: selected ? AppColors.surface2 : Colors.transparent,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Container(
              width: 30,
              height: 30,
              decoration: BoxDecoration(
                color: AppColors.surface2,
                borderRadius: BorderRadius.circular(9),
              ),
              child: Icon(_icon(p.id), size: 14, color: AppColors.textDim),
            ),
            const SizedBox(width: 11),
            Expanded(child: Text(p.name, style: AppTheme.sans(size: 13, w: FontWeight.w600))),
            Text(
              active ? 'ACTIVE' : p.tag,
              style: AppTheme.mono(
                size: 9.5,
                w: FontWeight.w600,
                color: active ? AppColors.text : AppColors.textMuted,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _field(String label, TextEditingController controller, {bool obscure = false, String? hint}) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 13, 14, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: AppTheme.mono(
              size: 10,
              color: AppColors.textMuted,
              w: FontWeight.w600,
              letterSpacing: 0.6,
            ),
          ),
          const SizedBox(height: 6),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
            decoration: BoxDecoration(
              color: AppColors.surface2,
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: AppColors.line),
            ),
            child: TextField(
              controller: controller,
              obscureText: obscure,
              cursorColor: AppColors.accent,
              style: AppTheme.mono(size: 12, color: AppColors.text),
              decoration: InputDecoration(
                isDense: true,
                border: InputBorder.none,
                hintText: hint,
                hintStyle: AppTheme.mono(size: 12, color: AppColors.textMuted),
              ),
            ),
          ),
        ],
      ),
    );
  }

  static IconData _icon(String id) {
    switch (id) {
      case 'gemini':
        return Icons.auto_awesome;
      case 'groq':
        return Icons.bolt;
      case 'openai':
        return Icons.hub_outlined;
      default:
        return Icons.memory;
    }
  }
}
