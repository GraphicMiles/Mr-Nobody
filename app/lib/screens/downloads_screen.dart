import 'package:flutter/material.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';

/// Downloads (S8) — Storage summary + Recent list with status icons.
class DownloadsScreen extends StatelessWidget {
  const DownloadsScreen({super.key});

  static const _files = [
    ('report.pdf', '2.1 MB', Icons.picture_as_pdf_outlined, Icons.download, false),
    ('image.jpg', '480 KB', Icons.image_outlined, Icons.check, false),
    ('archive.zip', '—', Icons.folder_zip_outlined, Icons.refresh, true),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: PanelShell(
        title: 'Downloads',
        onBack: () => Navigator.of(context).pop(),
        children: [
          const SectionLabel('Storage'),
          const _Card([
            MetricRow('Files downloaded', '3'),
            Divider(),
            MetricRow('Storage used', '2.6 MB'),
          ]),
          const SectionLabel('Recent'),
          _Card(
            List.generate(_files.length, (i) {
              final (name, size, typeIcon, statusIcon, failed) = _files[i];
              return Column(
                children: [
                  _DownloadRow(name: name, size: size, typeIcon: typeIcon, statusIcon: statusIcon, failed: failed),
                  if (i != _files.length - 1) const Divider(),
                ],
              );
            }),
          ),
        ],
      ),
    );
  }
}

class _DownloadRow extends StatelessWidget {
  final String name;
  final String size;
  final IconData typeIcon;
  final IconData statusIcon;
  final bool failed;
  const _DownloadRow({required this.name, required this.size, required this.typeIcon, required this.statusIcon, required this.failed});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      child: Row(
        children: [
          Container(
            width: 34,
            height: 34,
            decoration: BoxDecoration(
              color: AppColors.surface2,
              borderRadius: BorderRadius.circular(9),
              border: Border.all(color: AppColors.line),
            ),
            child: Icon(typeIcon, size: 15, color: AppColors.textDim),
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name, style: AppTheme.sans(size: 12.5, w: FontWeight.w600), maxLines: 1, overflow: TextOverflow.ellipsis),
                Text(size, style: AppTheme.mono(size: 10, color: AppColors.textFaint)),
              ],
            ),
          ),
          Icon(
            statusIcon,
            size: 14,
            color: failed ? AppColors.textFaint : AppColors.textDim,
          ),
        ],
      ),
    );
  }
}

class _Card extends StatelessWidget {
  final List<Widget> children;
  const _Card(this.children);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: AppCard(child: Column(children: children)),
    );
  }
}
