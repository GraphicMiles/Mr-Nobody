/// Builds a small set of actions from the response that actually exists.
///
/// Suggestions are conditional response data, not a fixed section: research
/// with multiple read sources can offer comparison; a download can offer file
/// follow-up; a conversational reply or failed task offers nothing.
abstract final class FollowUpSuggestions {
  static List<String> build({
    required String instruction,
    required String answer,
    required int sourceCount,
    required Iterable<String> activityKinds,
  }) {
    if (answer.trim().isEmpty) return const [];
    final kinds = activityKinds.toSet();

    if (kinds.any((kind) => kind == 'download' || kind.startsWith('download.'))) {
      return const [
        'Where was the file saved?',
        'Check the download again',
      ];
    }

    if (sourceCount <= 0) return const [];
    final topic = _topic(instruction);
    if (topic.isEmpty) return const [];

    final out = <String>['Explain $topic more simply'];
    if (sourceCount > 1) {
      out.add('Compare the sources on $topic');
    } else {
      out.add('Find another source about $topic');
    }
    return out;
  }

  static String _topic(String instruction) {
    var text = instruction.trim().replaceAll(RegExp(r'\s+'), ' ');
    // Leading politeness and stacked task verbs: "Please read and summarize
    // https://example.com/x in 5 bullets" must not surface as a chip that
    // says "Explain and summarize https://example.com/x… more simply".
    final lead = RegExp(
      r'^(please|kindly|can you|could you|research|explain|tell me about|'
      r'find out|find|look up|look for|read|summarize|summarise|download|'
      r'get|fetch|use google search to|use google to|use|search for|search|'
      r'show me|show|give me|do|and|then)\s+',
      caseSensitive: false,
    );
    for (var i = 0; i < 6; i++) {
      final m = lead.firstMatch(text);
      if (m == null) break;
      text = text.substring(m.end);
    }
    text = text.replaceFirst(
        RegExp(r'^(me|us)\s+', caseSensitive: false), '');
    text = text.replaceFirst(
        RegExp(r'^(a|an|the)\s+', caseSensitive: false), '');
    // A full URL reads badly inside a chip; the host is the recognisable part.
    text = text.replaceAllMapped(
        RegExp(r'https?://(?:www\.)?([^/\s]+)\S*'), (m) => m.group(1)!);
    final directive = RegExp(
      r'(?:[.!?]\s+|\s+(?:use at least|include citations|and include|with citations|cite sources).*|\s+in\s+\d+\s+(?:bullets?|bullet points?|words|sentences).*)',
      caseSensitive: false,
    ).firstMatch(text);
    if (directive != null) text = text.substring(0, directive.start);
    text = text.replaceAll(RegExp(r'[.?!,:;]+$'), '').trim();
    // Nothing meaningful left ("please", "do it") → no chips at all rather
    // than a mangled one.
    if (text.length < 4) return '';
    if (text.length > 72) text = '${text.substring(0, 71).trimRight()}…';
    return text;
  }
}
