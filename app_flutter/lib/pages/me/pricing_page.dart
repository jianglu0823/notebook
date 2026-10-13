import 'package:flutter/material.dart';
import '../../api/me_api.dart';

/// 计费说明:模型单价表 + 功能 ↔ 模型对照
class PricingPage extends StatelessWidget {
  const PricingPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('计费说明')),
      body: FutureBuilder<Map<String, dynamic>>(
        future: MeApi.pricing(),
        builder: (context, snap) {
          final d = snap.data;
          if (d == null) {
            return const Center(child: CircularProgressIndicator());
          }
          final models = d['models'] as List? ?? [];
          final features = d['features'] as List? ?? [];
          return ListView(padding: const EdgeInsets.all(16), children: [
            const Text('模型单价(元 / 1k tokens)',
                style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Card(
              child: Column(children: [
                for (final m in models)
                  ListTile(
                    dense: true,
                    title: Text(m['label'] as String? ?? m['name'] as String? ?? ''),
                    subtitle: Text(m['name'] as String? ?? ''),
                    trailing: Text('入 ${m['inputPer1k']} / 出 ${m['outputPer1k']}'),
                  ),
              ]),
            ),
            const SizedBox(height: 16),
            const Text('功能与模型对照',
                style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Card(
              child: Column(children: [
                for (final f in features)
                  ListTile(
                    dense: true,
                    title: Text(f['name'] as String? ?? ''),
                    subtitle: Text(f['note'] as String? ?? ''),
                    trailing: Text(f['model'] as String? ?? '',
                        style: const TextStyle(fontSize: 12)),
                  ),
              ]),
            ),
          ]);
        },
      ),
    );
  }
}
