import 'package:flutter/material.dart';
import '../forum/forum_page.dart';
import 'economy_page.dart';
import 'world_page.dart';

/// 世界 Tab:论坛 / 小世界 / 经济
class WorldTab extends StatelessWidget {
  const WorldTab({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('世界'),
          bottom: const TabBar(tabs: [
            Tab(text: '论坛'),
            Tab(text: '小世界'),
            Tab(text: '经济'),
          ]),
        ),
        body: const TabBarView(children: [
          ForumPage(),
          WorldPage(),
          EconomyPage(),
        ]),
      ),
    );
  }
}
