import 'package:flutter/material.dart';
import 'writing_list_page.dart';
import 'xhs_list_page.dart';

/// 创作中心:小红书 / 短剧 / 协作写作的聚合入口
class StudioPage extends StatelessWidget {
  const StudioPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('创作中心')),
      body: ListView(padding: const EdgeInsets.all(16), children: const [
        _StudioEntry(Icons.edit_note, '小红书创作', '选题 → 标题 → 正文五步生成',
            Color(0xFFFF4D67), XhsListPage()),
        _StudioEntry(Icons.movie_outlined, '短剧', '分镜脚本与剧集产出',
            Color(0xFF7C4DFF), XhsListPage(drama: true)),
        _StudioEntry(Icons.groups_outlined, '协作写作', '多智能体接力创作',
            Color(0xFF34A853), WritingListPage()),
      ]),
    );
  }
}

class _StudioEntry extends StatelessWidget {
  final IconData icon;
  final String title;
  final String subtitle;
  final Color color;
  final Widget page;
  const _StudioEntry(
      this.icon, this.title, this.subtitle, this.color, this.page);

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: ListTile(
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        leading: Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(12),
          ),
          child: Icon(icon, color: color),
        ),
        title: Text(title, style: const TextStyle(fontWeight: FontWeight.bold)),
        subtitle: Text(subtitle),
        trailing: const Icon(Icons.chevron_right),
        onTap: () =>
            Navigator.of(context).push(MaterialPageRoute(builder: (_) => page)),
      ),
    );
  }
}
