import 'package:files_tech_core/files_tech_core.dart' as core;
import 'package:flutter/material.dart';

/// Wrapper Read Files Tech autour de [core.CloudShareRow] : injecte le channel
/// `com.readfilestech/open_file`.
class CloudShareRow extends StatelessWidget {
  final String path;
  final String mime;
  const CloudShareRow({
    super.key,
    required this.path,
    this.mime = 'application/octet-stream',
  });

  @override
  Widget build(BuildContext context) => core.CloudShareRow(
        path: path,
        mime: mime,
        channelName: 'com.readfilestech/open_file',
      );
}
