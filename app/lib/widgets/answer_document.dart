import 'dart:convert';

import 'agent_response.dart';

/// A reusable parse of an agent answer into headings, paragraphs, lists and
/// inline emphasis.
///
/// Models still emit Markdown. The chat used to print that source, so
/// `**The Boys**` showed up as literal asterisks. This is the other half of
/// the renderer: turn that source into structure the UI can draw, and never
/// leave a markup character on the page.
class AnswerDocument {
  final List<AnswerBlock> blocks;

  const AnswerDocument(this.blocks);

  bool get isEmpty => blocks.isEmpty;

  /// True when the answer is one flat paragraph — the golden-safe path that
  /// still goes through [StreamedAnswer] so a restored one-liner does not
  /// change its geometry.
  bool get isPlain {
    if (blocks.length != 1) return false;
    final block = blocks.first;
    if (block is! AnswerParagraph) return false;
    return block.spans.every((s) => !s.bold && !s.italic);
  }

  /// Flatten for the word-by-word reveal used on a plain answer.
  List<StreamToken> toStreamTokens() {
    final out = <StreamToken>[];
    for (final block in blocks) {
      for (final span in block.spans) {
        if (span.cite != null) {
          out.add(StreamToken('', cite: span.cite));
          continue;
        }
        for (final word in span.text.split(RegExp(r'\s+'))) {
          if (word.isEmpty) continue;
          out.add(StreamToken(word, bold: span.bold, italic: span.italic));
        }
      }
    }
    return out;
  }

  /// Visible text with markup stripped — what copy-to-clipboard should hold
  /// when the user does not want asterisks in their paste either.
  String get plainText {
    final buf = StringBuffer();
    for (final block in blocks) {
      if (buf.isNotEmpty) buf.writeln();
      if (block is AnswerList) {
        for (var i = 0; i < block.items.length; i++) {
          final mark = block.ordered ? '${i + 1}. ' : '• ';
          buf.writeln('$mark${_join(block.items[i])}');
        }
      } else {
        buf.write(_join(block.spans));
      }
    }
    return buf.toString().trim();
  }

  static String _join(List<AnswerSpan> spans) =>
      spans.map((s) => s.text).join().replaceAll(RegExp(r' +'), ' ').trim();

  static AnswerDocument parse(String raw) {
    if (raw.isEmpty) return const AnswerDocument([]);
    final text = _unwrapFence(_normaliseNewlines(raw));
    final lines = text.split('\n');
    final blocks = <AnswerBlock>[];
    var i = 0;
    while (i < lines.length) {
      final line = lines[i];
      final trimmed = line.trim();
      if (trimmed.isEmpty) {
        i++;
        continue;
      }
      final heading = _heading(trimmed);
      if (heading != null) {
        blocks.add(heading);
        i++;
        continue;
      }
      if (_isNote(trimmed)) {
        blocks.add(AnswerNote(inline(trimmed)));
        i++;
        continue;
      }
      if (_isListItem(trimmed)) {
        final items = <List<AnswerSpan>>[];
        var ordered = _orderedItem(trimmed) != null;
        while (i < lines.length) {
          final item = lines[i].trim();
          if (item.isEmpty) {
            // A blank line ends the list only when the next non-empty line
            // is not another item — otherwise it is just spacing.
            var j = i + 1;
            while (j < lines.length && lines[j].trim().isEmpty) {
              j++;
            }
            if (j >= lines.length || !_isListItem(lines[j].trim())) break;
            i = j;
            continue;
          }
          final bullet = _bulletItem(item);
          final number = _orderedItem(item);
          if (bullet == null && number == null) break;
          ordered = number != null;
          items.add(inline(bullet ?? number!));
          i++;
        }
        if (items.isNotEmpty) {
          blocks.add(AnswerList(items, ordered: ordered));
        }
        continue;
      }
      final para = StringBuffer(trimmed);
      i++;
      while (i < lines.length) {
        final next = lines[i].trim();
        if (next.isEmpty) break;
        if (_heading(next) != null || _isListItem(next) || _isNote(next)) break;
        para.write(' ');
        para.write(next);
        i++;
      }
      blocks.add(AnswerParagraph(inline(para.toString())));
    }
    return AnswerDocument(blocks);
  }

  /// Inline emphasis, citations and bare URLs. Unpaired `**` at the end of
  /// a still-arriving stream is treated as "bold from here" so the asterisks
  /// never flash on screen.
  static List<AnswerSpan> inline(String text) {
    if (text.isEmpty) return const [];
    final out = <AnswerSpan>[];
    final buf = StringBuffer();
    var bold = false;
    var italic = false;
    var i = 0;

    void flush() {
      if (buf.isEmpty) return;
      out.add(AnswerSpan(buf.toString(), bold: bold, italic: italic));
      buf.clear();
    }

    while (i < text.length) {
      if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
        flush();
        bold = !bold;
        i += 2;
        continue;
      }
      if (i + 1 < text.length && text[i] == '_' && text[i + 1] == '_') {
        flush();
        bold = !bold;
        i += 2;
        continue;
      }
      if (text[i] == '*' && _lonelyItalic(text, i)) {
        flush();
        italic = !italic;
        i++;
        continue;
      }
      final cite = _citationAt(text, i);
      if (cite != null) {
        flush();
        out.add(AnswerSpan('', cite: cite.$1));
        i = cite.$2;
        continue;
      }
      final url = _urlAt(text, i);
      if (url != null) {
        flush();
        out.add(AnswerSpan('', cite: url.$1));
        i = url.$2;
        continue;
      }
      buf.write(text[i]);
      i++;
    }
    flush();
    return out;
  }

  static bool _lonelyItalic(String text, int i) {
    // `* item` is a list marker, already handled at block level. `*word*` is
    // italic. `2 * 3` is multiplication and must stay visible.
    if (i > 0 && text[i - 1] != ' ' && !_isOpen(text[i - 1])) return false;
    final close = text.indexOf('*', i + 1);
    if (close < 0) return false;
    if (close == i + 1) return false;
    return true;
  }

  static bool _isOpen(String ch) => ch == '(' || ch == '[' || ch == '{';

  static (AgentSource, int)? _citationAt(String text, int i) {
    if (text[i] != '[') return null;
    final close = text.indexOf(']', i + 1);
    if (close < 0 || close - i > 4) return null;
    final inner = text.substring(i + 1, close);
    if (!RegExp(r'^\d+$').hasMatch(inner)) return null;
    return (
      AgentSource(title: '[$inner]', domain: inner, url: ''),
      close + 1,
    );
  }

  static (AgentSource, int)? _urlAt(String text, int i) {
    if (i + 7 >= text.length) return null;
    if (!(text.startsWith('https://', i) || text.startsWith('http://', i))) {
      return null;
    }
    var end = i;
    while (end < text.length && !_urlStop(text[end])) {
      end++;
    }
    while (end > i && '.,);:]'.contains(text[end - 1])) {
      end--;
    }
    final raw = text.substring(i, end);
    final domain = Uri.tryParse(raw)?.host.replaceFirst(RegExp(r'^www\.'), '') ??
        raw.replaceFirst(RegExp(r'^https?://(www\.)?'), '').split('/').first;
    return (AgentSource(title: domain, domain: domain, url: raw), end);
  }

  static bool _urlStop(String ch) =>
      ch == ' ' || ch == '\n' || ch == '\t' || ch == '<' || ch == '"';

  static AnswerHeading? _heading(String line) {
    final m = RegExp(r'^(#{1,3})\s+(.+)$').firstMatch(line);
    if (m == null) return null;
    return AnswerHeading(m.group(1)!.length, inline(m.group(2)!));
  }

  static bool _isListItem(String line) =>
      _bulletItem(line) != null || _orderedItem(line) != null;

  static String? _bulletItem(String line) {
    final m = RegExp(r'^[-*•]\s+(.+)$').firstMatch(line);
    return m?.group(1);
  }

  static String? _orderedItem(String line) {
    final m = RegExp(r'^\d+[.)]\s+(.+)$').firstMatch(line);
    return m?.group(1);
  }

  static bool _isNote(String line) {
    final t = line.toLowerCase();
    return line.startsWith('⚠') ||
        line.startsWith('⚠️') ||
        t.startsWith('read at ') ||
        t.startsWith('this looked like') ||
        t.contains('token') && t.contains('\$') ||
        t.startsWith('some statements') ||
        t.startsWith('downloaded ') ||
        t.startsWith('download failed') ||
        t.startsWith('no downloadable') ||
        t.startsWith('i will check') ||
        t.startsWith("i'll check") ||
        t.startsWith('checking every') ||
        t.startsWith('no change') ||
        t.startsWith('changed since');
  }

  static String _normaliseNewlines(String s) =>
      s.replaceAll('\r\n', '\n').replaceAll('\r', '\n');

  static String _unwrapFence(String s) {
    final t = s.trim();
    if (!t.startsWith('```')) return s;
    final firstNl = t.indexOf('\n');
    if (firstNl < 0) return s;
    var body = t.substring(firstNl + 1);
    if (body.endsWith('```')) {
      body = body.substring(0, body.length - 3);
    }
    return body.trim();
  }
}

sealed class AnswerBlock {
  List<AnswerSpan> get spans;
}

class AnswerHeading implements AnswerBlock {
  final int level;
  @override
  final List<AnswerSpan> spans;
  const AnswerHeading(this.level, this.spans);
}

class AnswerParagraph implements AnswerBlock {
  @override
  final List<AnswerSpan> spans;
  const AnswerParagraph(this.spans);
}

class AnswerList implements AnswerBlock {
  final List<List<AnswerSpan>> items;
  final bool ordered;
  const AnswerList(this.items, {this.ordered = false});

  @override
  List<AnswerSpan> get spans => [for (final item in items) ...item];
}

class AnswerNote implements AnswerBlock {
  @override
  final List<AnswerSpan> spans;
  const AnswerNote(this.spans);
}

class AnswerSpan {
  final String text;
  final bool bold;
  final bool italic;
  final AgentSource? cite;
  const AnswerSpan(this.text,
      {this.bold = false, this.italic = false, this.cite});
}

/// A visual card drawn from a search artifact — title, host, snippet — not a
/// stock photo. Network images would leak the reading list to every CDN.
class EvidenceCardData {
  final String title;
  final String domain;
  final String url;
  final String note;
  /// Local path (downloaded through the privacy gate) or an https URL.
  final String image;

  const EvidenceCardData({
    required this.title,
    required this.domain,
    required this.url,
    this.note = '',
    this.image = '',
  });

  String get initial {
    final t = title.trim();
    if (t.isNotEmpty) return t[0].toUpperCase();
    return domain.isEmpty ? '?' : domain[0].toUpperCase();
  }

  static List<EvidenceCardData> fromArtifacts(String json) {
    if (json.isEmpty || json == '[]') return const [];
    try {
      final decoded = jsonDecode(json);
      final out = <EvidenceCardData>[];
      for (final row in decoded) {
        if (row is! Map) continue;
        final title = '${row['title'] ?? ''}';
        final url = '${row['url'] ?? ''}';
        if (title.isEmpty && url.isEmpty) continue;
        out.add(EvidenceCardData(
          title: title.isEmpty ? url : title,
          domain: _host(url),
          url: url,
          note: '${row['note'] ?? ''}',
          image: '${row['image'] ?? ''}',
        ));
      }
      return out;
    } catch (_) {
      return const [];
    }
  }

  /// Two or three cards when the task is about something you would look at,
  /// never for a one-line figure or a status note.
  static List<EvidenceCardData> pick({
    required String instruction,
    required String answer,
    required List<EvidenceCardData> artifacts,
  }) {
    if (artifacts.isEmpty) return const [];
    final pictured = artifacts.where((a) => a.image.isNotEmpty).toList();
    if (pictured.isNotEmpty) {
      final lower = answer.toLowerCase();
      pictured.sort((a, b) {
        final am = lower.contains(a.title.toLowerCase()) ? 0 : 1;
        final bm = lower.contains(b.title.toLowerCase()) ? 0 : 1;
        return am.compareTo(bm);
      });
      return pictured.take(3).toList();
    }
    if (artifacts.length < 2) return const [];
    if (!_visual(instruction, answer)) return const [];
    return artifacts.take(3).toList();
  }

  static bool _visual(String instruction, String answer) {
    final t = '${instruction.toLowerCase()} ${answer.toLowerCase()}';
    const keys = [
      'series',
      'show',
      'movie',
      'film',
      'watch',
      'album',
      'song',
      'game',
      'product',
      'laptop',
      'phone',
      'recipe',
      'place',
      'compare',
      'recommend',
      'latest',
      'poster',
      'cover',
      'prime',
      'netflix',
      'web series',
    ];
    for (final k in keys) {
      if (t.contains(k)) return true;
    }
    return false;
  }

  static String _host(String url) {
    final host = Uri.tryParse(url)?.host ?? '';
    return host.replaceFirst(RegExp(r'^www\.'), '');
  }
}
