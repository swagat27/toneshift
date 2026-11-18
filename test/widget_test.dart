import 'package:flutter_test/flutter_test.dart';
import 'package:toneshift/main.dart';

void main() {
  testWidgets('ToneShift app loads', (WidgetTester tester) async {
    await tester.pumpWidget(const ToneShiftApp());
    expect(find.text('ToneShift'), findsWidgets);
  });
}
