import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import 'answer_document.dart';
import 'agent_response.dart';
import 'common.dart';

/// The user-facing answer: headings, emphasis, lists, evidence cards.
///
/// The tool trace stays in [AgentTrace]. This widget only draws the
/// synthesised result, so a dump of the model's raw Markdown never reaches
/// the reader.
class AnswerView extends StatelessWidget {
  final AnswerDocument document;
  final List<EvidenceCardData> cards;
  final List<AgentSource> sources;
  final int visible;
  final bool caret;
  final void Function(AgentSource source)? onSourceTap;
  final void Function(EvidenceCardData card)? onCardTap;

  const AnswerView({
    super.key,
    required this.document,
    this.cards = const [],
    this.sources = const [],
    this.visible = 1 << 30,
    this.caret = false,
    this.onSourceTap,
    this.onCardTap,
  });

  @override
  Widget build(BuildContext context) {
    if (document.isEmpty) return const SizedBox.shrink();
    if (document.isPlain) {
      return StreamedAnswer(
        tokens: document.toStreamTokens(),
        visible: visible,
        caret: caret,
        onSourceTap: onSourceTap,
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var i = 0; i < document.blocks.length; i++) ...[
          if (i > 0) SizedBox(height: _gapAfter(document.blocks[i - 1])),
          _block(document.blocks[i]),
        ],
        if (cards.isNotEmpty) ...[
          const SizedBox(height: 14),
          EvidenceStrip(cards: cards, onTap: onCardTap),
        ],
      ],
    );
  }

  double _gapAfter(AnswerBlock block) {
    if (block is AnswerHeading) return 8;
    if (block is AnswerNote) return 8;
    return AgentMetrics.paragraphGap;
  }

  AgentSource _resolveCite(AgentSource source) {
    final n = int.tryParse(source.domain);
    if (n != null && n >= 1 && n <= sources.length) return sources[n - 1];
    return source;
  }

  List<AnswerSpan> _bound(List<AnswerSpan> spans) => [
        for (final s in spans)
          s.cite == null ? s : AnswerSpan('', cite: _resolveCite(s.cite!)),
      ];

  Widget _block(AnswerBlock block) {
    if (block is AnswerHeading) {
      return _RichLine(
        spans: _bound(block.spans),
        style: AppTheme.sans(
          size: block.level <= 1 ? 17.5 : 15,
          w: FontWeight.w700,
          height: 1.28,
        ),
        onSourceTap: onSourceTap,
      );
    }
    if (block is AnswerList) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (var i = 0; i < block.items.length; i++)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 18,
                    child: Text(
                      block.ordered ? '${i + 1}.' : '•',
                      style: AppTheme.sans(
                        size: AgentMetrics.bodySize,
                        w: FontWeight.w600,
                        color: AppColors.textDim,
                        height: AgentMetrics.bodyHeight,
                      ),
                    ),
                  ),
                  Expanded(
                    child: _RichLine(
                      spans: _bound(block.items[i]),
                      style: AppTheme.sans(
                        size: AgentMetrics.bodySize,
                        height: AgentMetrics.bodyHeight,
                      ),
                      onSourceTap: onSourceTap,
                    ),
                  ),
                ],
              ),
            ),
        ],
      );
    }
    if (block is AnswerNote) {
      return _RichLine(
        spans: _bound(block.spans),
        style: AppTheme.sans(
          size: 11.5,
          color: AppColors.textFaint,
          height: 1.5,
        ),
        onSourceTap: onSourceTap,
      );
    }
    return _RichLine(
      spans: _bound(block.spans),
      style: AppTheme.sans(
        size: AgentMetrics.bodySize,
        height: AgentMetrics.bodyHeight,
      ),
      onSourceTap: onSourceTap,
    );
  }
}

class _RichLine extends StatelessWidget {
  final List<AnswerSpan> spans;
  final TextStyle style;
  final void Function(AgentSource)? onSourceTap;

  const _RichLine({
    required this.spans,
    required this.style,
    this.onSourceTap,
  });

  @override
  Widget build(BuildContext context) {
    return Text.rich(
      TextSpan(
        children: [
          for (final span in spans)
            if (span.cite != null)
              WidgetSpan(
                alignment: PlaceholderAlignment.middle,
                child: _MiniCite(source: span.cite!, onTap: onSourceTap),
              )
            else
              TextSpan(
                text: span.text,
                style: style.copyWith(
                  fontWeight: span.bold ? FontWeight.w700 : style.fontWeight,
                  fontStyle:
                      span.italic ? FontStyle.italic : style.fontStyle,
                ),
              ),
        ],
      ),
      style: style,
    );
  }
}

class _MiniCite extends StatelessWidget {
  final AgentSource source;
  final void Function(AgentSource)? onTap;

  const _MiniCite({required this.source, this.onTap});

  @override
  Widget build(BuildContext context) {
    final label = source.url.isEmpty ? source.domain : source.domain;
    return GestureDetector(
      onTap: onTap == null ? null : () => onTap!(source),
      child: Container(
        height: 16,
        margin: const EdgeInsets.symmetric(horizontal: 2),
        padding: const EdgeInsets.symmetric(horizontal: 5),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: AppColors.surface2,
          borderRadius: BorderRadius.circular(4),
          border: Border.all(color: AppColors.line),
        ),
        child: Text(
          label,
          style: AppTheme.mono(size: 10, color: AppColors.textDim),
        ),
      ),
    );
  }
}

/// Two or three visual cards for the entities the answer is about.
///
/// Drawn, never fetched: a network image per card would leak the reading
/// list to every image host, which the privacy audit exists to prevent.
class EvidenceStrip extends StatelessWidget {
  final List<EvidenceCardData> cards;
  final void Function(EvidenceCardData card)? onTap;

  const EvidenceStrip({super.key, required this.cards, this.onTap});

  @override
  Widget build(BuildContext context) {
    if (cards.isEmpty) return const SizedBox.shrink();
    return SizedBox(
      height: 148,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        itemCount: cards.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (context, i) => _EvidenceCard(
          card: cards[i],
          onTap: onTap,
        ),
      ),
    );
  }
}

class _EvidenceCard extends StatelessWidget {
  final EvidenceCardData card;
  final void Function(EvidenceCardData card)? onTap;

  const _EvidenceCard({required this.card, this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap == null ? null : () => onTap!(card),
      child: Container(
        width: 148,
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: AppColors.line),
        ),
        clipBehavior: Clip.antiAlias,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              height: 78,
              width: double.infinity,
              color: AppColors.surface2,
              alignment: Alignment.center,
              child: Container(
                width: 36,
                height: 36,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: AppColors.surface3,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  card.initial,
                  style: AppTheme.sans(
                    size: 16,
                    w: FontWeight.w700,
                    color: AppColors.textDim,
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 8, 10, 0),
              child: Text(
                card.title,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: AppTheme.sans(
                  size: 12,
                  w: FontWeight.w600,
                  height: 1.3,
                ),
              ),
            ),
            if (card.domain.isNotEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(10, 3, 10, 8),
                child: Text(
                  card.domain,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: AppTheme.mono(size: 10, color: AppColors.textMuted),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// Why the agent stopped, and what the user can do about it.
///
/// Upload / grant / review are not generic Allow/Deny: the useful next
/// step is opening the page in a visible tab. Network is a retry.
class WaitingPrompt extends StatelessWidget {
  final String kind;
  final String message;
  final String? url;
  final VoidCallback onAllow;
  final VoidCallback onDeny;
  final VoidCallback? onOpen;

  const WaitingPrompt({
    super.key,
    required this.kind,
    required this.message,
    this.url,
    required this.onAllow,
    required this.onDeny,
    this.onOpen,
  });

  bool get _hasPage =>
      url != null && url!.isNotEmpty && onOpen != null;

  String get _title {
    switch (kind) {
      case 'upload':
        return 'Needs a visible tab';
      case 'grant':
        return 'Needs a signed-in session';
      case 'review':
        return 'Have a look first';
      case 'network':
        return 'Waiting for a connection';
      default:
        return 'Needs your approval';
    }
  }

  String get _body {
    if (message.trim().isNotEmpty) {
      // Drop the trailing URL — it is offered as a button, not as prose.
      return message
          .split('\n')
          .where((l) => !l.trim().startsWith('http'))
          .join(' ')
          .trim();
    }
    switch (kind) {
      case 'upload':
        return 'A background browser cannot fill a file input. Open the page and pick the file yourself.';
      case 'grant':
        return 'Open the page, sign in, then grant the site.';
      default:
        return 'Open Mr Nobody to allow this.';
    }
  }

  String get _allowLabel {
    switch (kind) {
      case 'upload':
        return "I've finished";
      case 'review':
        return 'Continue';
      case 'network':
        return 'Retry';
      default:
        return 'Allow';
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 11, 12, 12),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.lineStrong),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(_title, style: AppTheme.sans(size: 13.5, w: FontWeight.w700)),
          const SizedBox(height: 6),
          Text(
            _body,
            style: AppTheme.sans(
              size: 12.5,
              color: AppColors.textDim,
              height: 1.5,
            ),
          ),
          const SizedBox(height: 12),
          if (_hasPage) ...[
            ActionButton('Open page', solid: true, onTap: onOpen!),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(child: ActionButton('Cancel', onTap: onDeny)),
                const SizedBox(width: 8),
                Expanded(
                  child: ActionButton(_allowLabel, onTap: onAllow),
                ),
              ],
            ),
          ] else
            Row(
              children: [
                Expanded(child: ActionButton('Deny', onTap: onDeny)),
                const SizedBox(width: 8),
                Expanded(
                  child: ActionButton(_allowLabel, solid: true, onTap: onAllow),
                ),
              ],
            ),
        ],
      ),
    );
  }
}
