import 'dart:io';
import 'package:flutter/material.dart';

class BreadcrumbBar extends StatelessWidget {
  final String path;
  final ValueChanged<Directory> onTap;

  const BreadcrumbBar({super.key, required this.path, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final parts = path.split('/').where((p) => p.isNotEmpty).toList();
    if (parts.length <= 1) return const SizedBox.shrink();
    return SizedBox(
      height: 36,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12),
        itemCount: parts.length,
        separatorBuilder: (_, i) =>
            const Icon(Icons.chevron_right, size: 16, color: Colors.grey),
        itemBuilder: (_, i) {
          final targetPath = '/${parts.sublist(0, i + 1).join('/')}';
          return GestureDetector(
            onTap: () => onTap(Directory(targetPath)),
            child: Center(
              child: Text(
                parts[i],
                style: TextStyle(
                  fontSize: 12,
                  color: i == parts.length - 1
                      ? Theme.of(context).colorScheme.primary
                      : Colors.grey,
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
