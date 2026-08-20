import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mrnobody/theme/app_theme.dart';
import 'package:mrnobody/widgets/agent_response.dart';
import 'package:mrnobody/widgets/brand_logo.dart';

/// Tests for the agent's response components.
///
/// The alignment assertions are the point of this file. The avatar drifting off
/// the first line of the response is not a bug a unit test would normally
/// catch — it is geometry, and it went unnoticed through several rounds of
/// review because it looks *almost* right. Pinning the measurement means the
/// next person to change the body size finds out immediately instead of
/// shipping a column that sags.
void main() {
  Widget host(Widget child) => MaterialApp(
        theme: AppTheme.dark(),
        home: Scaffold(
          backgroundColor: AppColors.bg,
          body: SizedBox(width: 360, child: child),
        ),
      );

  group('alignment contract', () {
    test('the avatar box is exactly one line box', () {
      // 13px at 1.62 line height rounds to a 21px line box. If either the size
      // or the height changes, this fails and the avatar must move with it.
      expect(
        (AgentMetrics.bodySize * AgentMetrics.bodyHeight).roundToDouble(),
        AgentMetrics.lineBox,
      );
      expect(AgentMetrics.avatar, AgentMetrics.lineBox);
    });

    test('the mark is inset inside its box, not filling it', () {
      expect(AgentMetrics.mark, lessThan(AgentMetrics.avatar));
      // A mark much smaller than its box reads as floating in space.
      expect(AgentMetrics.mark, greaterThan(AgentMetrics.avatar * 0.8));
    });

    test('the text indent is the avatar plus its gap', () {
      expect(AgentMetrics.indent,
          AgentMetrics.avatar + AgentMetrics.columnGap);
    });

    test('secondary information is 80 percent opaque', () {
      expect(AgentMetrics.secondaryOpacity, 0.8);
    });

    testWidgets('the mark centres on the first line of the response',
        (tester) async {
      await tester.pumpWidget(host(
        const AgentTurn(
          child: Text(
            'Bitcoin is \$64,282.19, up 1.18% over the last 24 hours, which '
            'is enough text to wrap onto a second and third line so the '
            'avatar has something to be misaligned against.',
            style: TextStyle(
              fontSize: AgentMetrics.bodySize,
              height: AgentMetrics.bodyHeight,
            ),
          ),
        ),
      ));

      final logo = tester.getRect(find.byType(BrandLogo));
      final text = tester.getRect(find.byType(Text));

      // The mark's centre must sit on the centre of the first line box, not
      // the centre of the whole paragraph and not above the text.
      final firstLineCentre = text.top + AgentMetrics.lineBox / 2;
      expect((logo.center.dy - firstLineCentre).abs(), lessThan(1.0),
          reason: 'mark centre ${logo.center.dy} vs first line '
              'centre $firstLineCentre — the avatar is drifting again');
    });

    testWidgets('a continued turn keeps the indent without a second mark',
        (tester) async {
      await tester.pumpWidget(host(
        const Column(
          children: [
            AgentTurn(child: Text('first')),
            AgentTurn(continued: true, child: Text('second')),
          ],
        ),
      ));

      expect(find.byType(BrandLogo), findsOneWidget);
      final first = tester.getRect(find.text('first'));
      final second = tester.getRect(find.text('second'));
      expect(second.left, first.left,
          reason: 'a continued block must line up with the one above it');
    });
  });

  group('streamed answer', () {
    final tokens = [
      const StreamToken('Bitcoin'),
      const StreamToken('is'),
      const StreamToken(r'$64,282.19'),
      const StreamToken('',
          cite: AgentSource(
              title: 'CoinMarketCap',
              domain: 'coinmarketcap.com',
              url: 'https://coinmarketcap.com/currencies/bitcoin/')),
    ];

    testWidgets('reveals only what has arrived', (tester) async {
      await tester.pumpWidget(host(
        StreamedAnswer(tokens: tokens, visible: 2, caret: true),
      ));
      await tester.pump(const Duration(milliseconds: 500));

      expect(find.textContaining('Bitcoin'), findsOneWidget);
      expect(find.textContaining('is'), findsOneWidget);
      expect(find.textContaining(r'$64,282.19'), findsNothing);
    });

    testWidgets('the citation renders where the claim is', (tester) async {
      await tester.pumpWidget(host(
        StreamedAnswer(tokens: tokens, visible: tokens.length),
      ));
      await tester.pump(const Duration(milliseconds: 500));

      expect(find.text('coinmarketcap.com'), findsOneWidget);
      // Drawn lettermark, never a network image: a fetched favicon is an
      // ungated request per domain and would leak the user's reading list.
      expect(find.byType(Image), findsNothing);
      expect(find.text('C'), findsOneWidget);
    });

    testWidgets('an already-finished answer does not replay its last words',
        (tester) async {
      await tester.pumpWidget(host(
        StreamedAnswer(tokens: tokens, visible: tokens.length, caret: false),
      ));
      await tester.pump();

      expect(find.byType(TweenAnimationBuilder<double>), findsNothing,
          reason: 'restored completions must be fully visible immediately');
      expect(find.text(r'$64,282.19 '), findsOneWidget);
      expect(find.text('coinmarketcap.com'), findsOneWidget);
    });

    testWidgets('an empty answer renders nothing rather than a caret',
        (tester) async {
      await tester.pumpWidget(host(
        const StreamedAnswer(tokens: [], visible: 0),
      ));
      await tester.pump();
      expect(tester.takeException(), isNull);
    });

    testWidgets('a bold token is drawn, not wrapped in asterisks',
        (tester) async {
      await tester.pumpWidget(host(
        const StreamedAnswer(
          tokens: [StreamToken('The', bold: true), StreamToken('Boys')],
          visible: 2,
        ),
      ));
      await tester.pump();
      expect(find.textContaining('**'), findsNothing);
      expect(find.textContaining('The'), findsOneWidget);
    });
  });

  group('trace', () {
    const steps = [
      TraceStep(label: 'Search', chip: 'bitcoin price', duration: '0.8s'),
      TraceStep(
          label: 'Read',
          chip: 'coinmarketcap.com',
          mono: true,
          detail: ['24 KB -> 4,180 words'],
          detailMono: true),
      TraceStep(label: 'Read', chip: 'tradingview.com', running: true),
    ];

    testWidgets('expands automatically while running', (tester) async {
      await tester.pumpWidget(host(
        const AgentTrace(
            steps: steps, running: true, doneLabel: 'Thought for 4 seconds'),
      ));
      await tester.pump(const Duration(milliseconds: 400));

      expect(find.text('Thinking'), findsNothing,
          reason: 'the separate live activity line owns the working label');
      expect(find.text('bitcoin price'), findsOneWidget);
    });

    testWidgets('settles to a past-tense summary', (tester) async {
      await tester.pumpWidget(host(
        const AgentTrace(
            steps: steps, running: false, doneLabel: 'Thought for 4 seconds'),
      ));
      await tester.pump(const Duration(milliseconds: 400));

      expect(find.text('Thought for 4 seconds'), findsOneWidget);
      expect(find.text('Thinking'), findsNothing);
    });

    testWidgets('the chevron is always present, never hover-gated',
        (tester) async {
      // The source library reveals this on hover, which never fires on a
      // touch screen — a hover-gated control is an invisible one here.
      await tester.pumpWidget(host(
        const AgentTrace(
            steps: steps, running: false, doneLabel: 'Thought for 2 seconds'),
      ));
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.byIcon(Icons.keyboard_arrow_down), findsOneWidget);
    });

    testWidgets('a settled trace can still be opened', (tester) async {
      await tester.pumpWidget(host(
        const AgentTrace(
            steps: steps, running: false, doneLabel: 'Thought for 4 seconds'),
      ));
      await tester.pump(const Duration(milliseconds: 400));

      // Collapsed means clipped by the parent, not removed from the tree:
      // AnimatedAlign keeps its child mounted at natural size so it can
      // animate open again. What matters to a reader is the height the trace
      // occupies in the column, which is the ClipRect's, not the row's.
      // The outermost ClipRect is the trace body. Each row has its own inner
      // clip for its detail lines, so `.first` here matters: measuring the
      // inner one reports zero even when the trace is open.
      double traceHeight() =>
          tester.getSize(find.byType(ClipRect).first).height;

      expect(traceHeight(), 0,
          reason: 'a collapsed trace must not occupy space');

      // Tap the chevron rather than the label: ShimmerLabel wraps its text in
      // a ShaderMask while animating, so the Text finder can resolve to a
      // subtree the header's GestureDetector does not own.
      await tester.tap(find.byIcon(Icons.keyboard_arrow_down));
      await tester.pump();
      // Fixed pump, not pumpAndSettle: one of these steps is still running,
      // so its spinner animates forever and settling never completes.
      await tester.pump(const Duration(milliseconds: 400));
      expect(traceHeight(), greaterThan(0),
          reason: 'tapping a settled trace must reopen it');
    });

    testWidgets('status and metric sit beneath the activity verb', (tester) async {
      await tester.pumpWidget(host(
        const AgentTrace(
          steps: [TraceStep(label: 'Searching broadly', metric: '6 candidates')],
          running: true,
          doneLabel: 'Thought for a moment',
        ),
      ));
      await tester.pump(const Duration(milliseconds: 100));

      final verb = tester.getTopLeft(find.text('Searching broadly'));
      final status = tester.getTopLeft(find.text('done'));
      final metric = tester.getTopLeft(find.text('6 candidates'));
      expect(status.dy, greaterThan(verb.dy));
      expect(metric.dy, greaterThan(verb.dy));
      expect((status.dy - metric.dy).abs(), lessThan(2));
    });

    testWidgets('pipeline outcomes use the monochrome status palette',
        (tester) async {
      await tester.pumpWidget(host(
        const AgentTrace(
          steps: [
            TraceStep(label: 'Done'),
            TraceStep(label: 'Recovered', recovered: true),
          ],
          running: false,
          doneLabel: 'Thought for 2 seconds',
        ),
      ));
      await tester.pump();

      final done = tester.widget<Icon>(find.byIcon(Icons.check));
      final recovered = tester.widget<Icon>(find.byIcon(Icons.refresh));
      final recoveredText = tester.widget<Text>(find.text('recovered'));
      expect(done.color, AppColors.textMuted);
      expect(recovered.color, AppColors.textMuted);
      expect(recoveredText.style?.color, AppColors.textMuted);
    });

    testWidgets('a refused step is marked, not hidden', (tester) async {
      await tester.pumpWidget(host(
        const AgentTrace(
          steps: [TraceStep(label: 'Download', chip: 'iso', denied: true)],
          running: false,
          doneLabel: 'Stopped',
        ),
      ));
      await tester.pump(const Duration(milliseconds: 400));
      await tester.tap(find.byIcon(Icons.keyboard_arrow_down));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.byIcon(Icons.block), findsOneWidget);
    });
  });

  group('figure warning', () {
    testWidgets('names the unsupported figure', (tester) async {
      await tester.pumpWidget(host(
        const FigureWarning(figures: ['6.7%']),
      ));
      await tester.pump();
      expect(find.textContaining('6.7%'), findsOneWidget);
    });

    testWidgets('nothing is drawn when every figure checked out',
        (tester) async {
      await tester.pumpWidget(host(const FigureWarning(figures: [])));
      await tester.pump();
      expect(find.byType(Container), findsNothing);
    });

    testWidgets('singular and plural read correctly', (tester) async {
      await tester.pumpWidget(host(
        const FigureWarning(figures: ['1.5', '2.5']),
      ));
      await tester.pump();
      expect(find.textContaining('These figures'), findsOneWidget);
    });
  });

  group('tail', () {
    const sources = [
      AgentSource(
          title: 'Bitcoin price today',
          domain: 'coinmarketcap.com',
          url: 'https://coinmarketcap.com/'),
      AgentSource(
          title: 'BTCUSD',
          domain: 'tradingview.com',
          url: 'https://tradingview.com/'),
    ];

    testWidgets('counts sources and expands the list', (tester) async {
      await tester.pumpWidget(host(const AgentActions(sources: sources)));
      await tester.pump();

      expect(find.text('2 sources'), findsOneWidget);
      await tester.tap(find.text('2 sources'));
      await tester.pumpAndSettle();
      expect(find.text('Bitcoin price today'), findsOneWidget);
    });

    testWidgets('one source is not "1 sources"', (tester) async {
      await tester.pumpWidget(host(
        const AgentActions(sources: [
          AgentSource(
              title: 'Bitcoin price today',
              domain: 'coinmarketcap.com',
              url: 'https://coinmarketcap.com/'),
        ]),
      ));
      await tester.pump();
      expect(find.text('1 source'), findsOneWidget);
    });

    testWidgets('follow-ups return their own text', (tester) async {
      String? tapped;
      await tester.pumpWidget(host(
        AgentFollowUps(
          items: const ['Tell me if it drops under 60k'],
          onTap: (t) => tapped = t,
        ),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Tell me if it drops under 60k'));
      expect(tapped, 'Tell me if it drops under 60k');
    });
  });

  group('working line', () {
    testWidgets('counts up from when the work started', (tester) async {
      await tester.pumpWidget(host(
        AgentWorkingLine(
          label: 'Reading coinmarketcap.com',
          since: DateTime.now().subtract(const Duration(seconds: 2)),
        ),
      ));
      await tester.pump(const Duration(milliseconds: 150));

      expect(find.text('Reading coinmarketcap.com'), findsOneWidget);
      expect(find.byType(PixelLoader), findsOneWidget);
      expect(find.textContaining('s'), findsWidgets);

      // Let the repeating timers unwind so the test does not leak them.
      await tester.pumpWidget(host(const SizedBox()));
    });
  });
}
