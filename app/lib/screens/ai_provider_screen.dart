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
  final _model = TextEditingController();

  late String _selected = widget.initialProvider ?? _state.providerId;
  bool _hasStoredKey = false;

  @override
  void initState() {
    super.initState();
    _loadConfig();
  }

  @override
  void dispose() {
    _key.dispose();
    _base.dispose();
    _model.dispose();
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
      _model.text = cfg['model'] as String? ?? '';
      _hasStoredKey = cfg['hasKey'] as bool? ?? false;
      _key.text = '';
    });
  }

  bool get _isLocal => _selected == 'local';

  Future<void> _save() async {
    if (_isLocal) {
      await _state.setProvider('local');
      if (!mounted) return;
      AppToast.show(context, 'Local provider active');
      Navigator.of(context).pop();
      return;
    }
    if (_key.text.trim().isEmpty && !_hasStoredKey) {
      AppToast.show(context, 'Add an API key first');
      return;
    }
    await NativeBridge.guard(
      () => NativeBridge.saveProvider(
        id: _selected,
        key: _key.text.trim().isEmpty ? null : _key.text.trim(),
        base: _base.text.trim(),
        model: _model.text.trim(),
        active: true,
      ),
      null,
      'could not save provider',
    );
    await _state.load();
    if (!mounted) return;
    AppToast.show(context, '${AiProviderOption.byId(_selected).shortName} active');
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
            const SectionLabel('Configuration'),
            AppCard(
              child: Column(
                children: [
                  _field('API KEY', _key,
                      obscure: true,
                      hint: _hasStoredKey ? 'saved — type to replace' : 'sk-…'),
                  _field('BASE URL', _base),
                  _field('MODEL', _model),
                  const SizedBox(height: 13),
                ],
              ),
            ),
          ],
          Padding(
            padding: const EdgeInsets.all(16),
            child: ActionButton('Save', solid: true, onTap: _save),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 14),
            child: Text(
              _isLocal
                  ? 'Local keeps every request on this device. Basic browsing never needs an AI provider.'
                  : 'If a remote provider is enabled, task context may leave the device.',
              style: AppTheme.sans(size: 11, color: AppColors.textMuted, height: 1.5),
            ),
          ),
        ],
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
