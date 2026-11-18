import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// ToneShift dashboard.
/// All persistent data (API key, presets, usage log) lives in the native
/// SecureStore via this MethodChannel, so the native PROCESS_TEXT activity
/// and the AccessibilityService can read it without waking Flutter.
const MethodChannel _channel = MethodChannel('com.toneshift/native');

void main() {
  runApp(const ToneShiftApp());
}

class ToneShiftApp extends StatelessWidget {
  const ToneShiftApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ToneShift',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.amber,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.amber,
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      themeMode: ThemeMode.dark,
      home: const HomeScreen(),
    );
  }
}

class Preset {
  Preset({required this.id, required this.label, required this.instruction});

  String id;
  String label;
  String instruction;

  factory Preset.fromJson(Map<String, dynamic> json) => Preset(
        id: json['id'] as String? ?? '',
        label: json['label'] as String? ?? '',
        instruction: json['instruction'] as String? ?? '',
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'label': label,
        'instruction': instruction,
      };
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  bool _hasKey = false;
  String? _maskedKey;
  bool _accessibilityOn = false;
  List<Preset> _presets = [];
  List<dynamic> _usageLog = [];
  bool _loading = true;

  final TextEditingController _keyController = TextEditingController();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _keyController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    try {
      final hasKey = await _channel.invokeMethod<bool>('hasApiKey') ?? false;
      final masked = await _channel.invokeMethod<String>('getMaskedApiKey');
      final acc =
          await _channel.invokeMethod<bool>('isAccessibilityEnabled') ?? false;
      final presetsJson = await _channel.invokeMethod<String>('getPresets');
      final logJson = await _channel.invokeMethod<String>('getUsageLog');

      List<Preset> presets = [];
      if (presetsJson != null && presetsJson.isNotEmpty) {
        final decoded = jsonDecode(presetsJson) as List<dynamic>;
        presets = decoded
            .map((e) => Preset.fromJson(e as Map<String, dynamic>))
            .toList();
      }
      List<dynamic> log = [];
      if (logJson != null && logJson.isNotEmpty) {
        log = jsonDecode(logJson) as List<dynamic>;
      }
      if (!mounted) return;
      setState(() {
        _hasKey = hasKey;
        _maskedKey = masked;
        _accessibilityOn = acc;
        _presets = presets;
        _usageLog = log;
        _loading = false;
      });
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() => _loading = false);
      _showSnack('Native error: ${e.message}');
    }
  }

  void _showSnack(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _saveKey() async {
    final key = _keyController.text.trim();
    if (key.isEmpty) {
      _showSnack('Paste your Gemini API key first.');
      return;
    }
    await _channel.invokeMethod('saveApiKey', {'key': key});
    _keyController.clear();
    _showSnack('API key saved securely on this device.');
    _refresh();
  }

  Future<void> _testKey() async {
    final typed = _keyController.text.trim();
    _showSnack('Testing key...');
    try {
      final msg = await _channel.invokeMethod<String>('testApiKey', {
        'key': typed.isEmpty ? null : typed,
      });
      _showSnack(msg ?? 'Key works.');
    } on PlatformException catch (e) {
      _showSnack(e.message ?? 'Test failed.');
    }
  }

  Future<void> _savePresets() async {
    final json = jsonEncode(_presets.map((p) => p.toJson()).toList());
    await _channel.invokeMethod('savePresets', {'json': json});
    _showSnack('Presets saved. The pill and popup menu will use them now.');
  }

  Future<void> _editPreset(int index) async {
    final preset = _presets[index];
    final labelController = TextEditingController(text: preset.label);
    final instructionController =
        TextEditingController(text: preset.instruction);
    final saved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Edit preset'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: labelController,
                decoration: const InputDecoration(labelText: 'Button label'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: instructionController,
                maxLines: 5,
                decoration: const InputDecoration(
                  labelText: 'Instruction for Gemini',
                  alignLabelWithHint: true,
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    if (saved == true) {
      setState(() {
        _presets[index].label = labelController.text.trim();
        _presets[index].instruction = instructionController.text.trim();
      });
      await _savePresets();
    }
  }

  Future<void> _addPreset() async {
    final labelController = TextEditingController();
    final instructionController = TextEditingController();
    final saved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('New preset'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: labelController,
                decoration:
                    const InputDecoration(labelText: 'Button label, e.g. Hinglish'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: instructionController,
                maxLines: 5,
                decoration: const InputDecoration(
                  labelText: 'Instruction, e.g. Rewrite in casual Hinglish',
                  alignLabelWithHint: true,
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Add'),
          ),
        ],
      ),
    );
    if (saved == true && labelController.text.trim().isNotEmpty) {
      final id = labelController.text
          .trim()
          .toUpperCase()
          .replaceAll(RegExp(r'[^A-Z0-9]+'), '_');
      setState(() {
        _presets.add(Preset(
          id: id,
          label: labelController.text.trim(),
          instruction: instructionController.text.trim(),
        ));
      });
      await _savePresets();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return Scaffold(
      appBar: AppBar(title: const Text('ToneShift')),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _statusCard(),
            const SizedBox(height: 16),
            _apiKeyCard(),
            const SizedBox(height: 16),
            _presetsCard(),
            const SizedBox(height: 16),
            _howToCard(),
            const SizedBox(height: 16),
            _usageCard(),
          ],
        ),
      ),
    );
  }

  Widget _statusCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Setup status',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            _statusRow('Gemini API key', _hasKey),
            _statusRow('Floating pill (Accessibility Service)', _accessibilityOn),
            const ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(Icons.check_circle, color: Colors.green),
              title: Text('Text selection menu'),
              subtitle: Text(
                  'Always on once installed. Highlight text in any app and look for ToneShift in the popup.'),
            ),
            if (!_accessibilityOn)
              FilledButton.icon(
                onPressed: () =>
                    _channel.invokeMethod('openAccessibilitySettings'),
                icon: const Icon(Icons.accessibility_new),
                label: const Text('Enable floating pill'),
              ),
          ],
        ),
      ),
    );
  }

  Widget _statusRow(String label, bool ok) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(
        ok ? Icons.check_circle : Icons.error_outline,
        color: ok ? Colors.green : Colors.orange,
      ),
      title: Text(label),
      subtitle: Text(ok ? 'Ready' : 'Not set up yet'),
    );
  }

  Widget _apiKeyCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Your Gemini API key',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(
              _hasKey
                  ? 'Saved key: ${_maskedKey ?? ''}. It is stored encrypted on this device only, and sent only to Google Gemini.'
                  : 'Free key: open Google AI Studio, create an API key, paste it here. It never leaves this device except to call Gemini.',
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _keyController,
              obscureText: true,
              decoration: const InputDecoration(
                labelText: 'Paste Gemini API key',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              children: [
                FilledButton(onPressed: _saveKey, child: const Text('Save key')),
                OutlinedButton(
                    onPressed: _testKey, child: const Text('Test connection')),
                if (_hasKey)
                  TextButton(
                    onPressed: () async {
                      await _channel.invokeMethod('clearApiKey');
                      _refresh();
                    },
                    child: const Text('Remove key'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _presetsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('Tone presets',
                    style: Theme.of(context).textTheme.titleMedium),
                TextButton(
                  onPressed: () async {
                    await _channel.invokeMethod('resetPresets');
                    _refresh();
                  },
                  child: const Text('Reset'),
                ),
              ],
            ),
            const SizedBox(height: 4),
            const Text(
                'These buttons appear in the popup menu and on the floating pill. Tap one to change what it tells Gemini.'),
            const SizedBox(height: 8),
            ..._presets.asMap().entries.map(
                  (entry) => ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(entry.value.label),
                    subtitle: Text(
                      entry.value.instruction,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    trailing: const Icon(Icons.edit_outlined),
                    onTap: () => _editPreset(entry.key),
                  ),
                ),
            OutlinedButton.icon(
              onPressed: _addPreset,
              icon: const Icon(Icons.add),
              label: const Text('Add your own preset'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _howToCard() {
    return const Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('How to use it, with Gboard untouched',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
            SizedBox(height: 12),
            Text(
                '1. Popup menu: type normally in WhatsApp, Gmail, LinkedIn, anywhere. Highlight the text, tap ToneShift in the popup menu, pick Shorten or Formal. The highlighted text is replaced in place.'),
            SizedBox(height: 8),
            Text(
                '2. Floating pill: with the pill enabled, tap it while a text field is focused, pick a tone, and the whole field is rewritten. Tap Undo to get your original back. Drag the pill to move it.'),
            SizedBox(height: 8),
            Text(
                '3. Custom instruction trick: end any draft with // and your instruction.\nExample: "cant come today sick // formal excuse to professor"\nExample: "kal meeting hai bhai bhoolna mat // make it polite and professional"'),
          ],
        ),
      ),
    );
  }

  Widget _usageCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text('Recent rewrites (${_usageLog.length})',
                    style: Theme.of(context).textTheme.titleMedium),
                if (_usageLog.isNotEmpty)
                  TextButton(
                    onPressed: () async {
                      await _channel.invokeMethod('clearUsageLog');
                      _refresh();
                    },
                    child: const Text('Clear'),
                  ),
              ],
            ),
            if (_usageLog.isEmpty)
              const Text('Nothing yet. Your rewrites will show here, mode and size only, never the text itself.')
            else
              ..._usageLog.take(20).map((e) {
                final map = e as Map<dynamic, dynamic>;
                final ts = DateTime.fromMillisecondsSinceEpoch(
                    (map['ts'] as num?)?.toInt() ?? 0);
                return ListTile(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  leading: Icon(
                    map['success'] == true
                        ? Icons.check_circle_outline
                        : Icons.error_outline,
                    size: 20,
                  ),
                  title: Text('${map['mode']} , ${map['inputChars']} to ${map['outputChars']} chars'),
                  subtitle: Text(ts.toString()),
                );
              }),
          ],
        ),
      ),
    );
  }
}
