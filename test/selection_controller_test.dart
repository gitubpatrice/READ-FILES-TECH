import 'package:flutter_test/flutter_test.dart';
import 'package:read_files_tech/screens/explorer/services/selection_controller.dart';

void main() {
  group('SelectionController', () {
    test('starts empty', () {
      final c = SelectionController();
      expect(c.count, 0);
      expect(c.hasSelection, isFalse);
      expect(c.isSelected('/foo'), isFalse);
    });

    test('toggle adds then removes', () {
      final c = SelectionController();
      c.toggle('/a');
      expect(c.count, 1);
      expect(c.isSelected('/a'), isTrue);
      c.toggle('/a');
      expect(c.count, 0);
      expect(c.hasSelection, isFalse);
    });

    test('selectAll replaces existing set', () {
      final c = SelectionController();
      c.toggle('/x');
      c.selectAll(['/a', '/b', '/c']);
      expect(c.count, 3);
      expect(c.isSelected('/x'), isFalse);
      expect(c.isSelected('/b'), isTrue);
    });

    test('clear empties and notifies once', () {
      final c = SelectionController();
      c.selectAll(['/a', '/b']);
      int notifications = 0;
      c.addListener(() => notifications++);
      c.clear();
      expect(c.count, 0);
      expect(notifications, 1);
      c.clear();
      expect(notifications, 1);
    });

    test('snapshot is independent of internal set', () {
      final c = SelectionController();
      c.selectAll(['/a', '/b']);
      final snap = c.snapshot();
      c.clear();
      expect(snap.length, 2);
    });
  });
}
