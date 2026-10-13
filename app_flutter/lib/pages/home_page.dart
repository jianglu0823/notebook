import 'dart:async';
import 'package:flutter/material.dart';
import '../api/world_api.dart';
import '../core/auth.dart';
import 'companion/companion_list_page.dart';
import 'me/me_tab.dart';
import 'me/news_page.dart';
import 'me/schedule_page.dart';
import 'me/studio_page.dart';
import 'note/all_notes_page.dart';
import 'notebook/notebook_list_page.dart';
import 'world/game_world_page.dart';
import 'world/world_tab.dart';

/// 首页:顶部功能导航带 + 小世界实时地图 + 居民实时活动(30s 轮询)
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage>
    with SingleTickerProviderStateMixin {
  static const _features = [
    _Feature(Icons.menu_book_rounded, '笔记本', Color(0xFF5B7FFF),
        NotebookListPage()),
    _Feature(Icons.edit_note_rounded, '笔记', Color(0xFF34A853),
        AllNotesPage()),
    _Feature(Icons.favorite_rounded, '伴侣', Color(0xFFE91E63),
        CompanionListPage()),
    _Feature(Icons.public_rounded, '世界', Color(0xFF7C4DFF),
        GameWorldPage()),
    _Feature(Icons.auto_awesome_rounded, '创作', Color(0xFFFF8A00),
        StudioPage()),
    _Feature(Icons.calendar_month_rounded, '日程', Color(0xFF00B8D4),
        SchedulePage()),
    _Feature(Icons.newspaper_rounded, '新闻', Color(0xFF5C6BC0), NewsPage()),
    _Feature(Icons.person_rounded, '我的', Color(0xFF8B5E3C), MeTab()),
  ];

  double _worldW = 1360;
  double _worldH = 700;
  List<dynamic> _places = [];
  List<dynamic> _agents = [];
  List<dynamic> _feed = [];
  Timer? _timer;

  /// 居民头像呼吸动效(位置轮询间隙也有生气)
  late final AnimationController _pulse = AnimationController(
      vsync: this, duration: const Duration(milliseconds: 1600))
    ..repeat(reverse: true);

  @override
  void initState() {
    super.initState();
    _load();
    _timer = Timer.periodic(const Duration(seconds: 30), (_) => _refreshLive());
  }

  @override
  void dispose() {
    _timer?.cancel();
    _pulse.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final p = await WorldApi.places();
      if (!mounted) return;
      setState(() {
        _worldW = (p['worldW'] as num?)?.toDouble() ?? 1360;
        _worldH = (p['worldH'] as num?)?.toDouble() ?? 700;
        _places = p['places'] as List? ?? [];
      });
    } catch (_) {}
    await _refreshLive();
  }

  /// 只刷会变的:居民位置 + 活动流
  Future<void> _refreshLive() async {
    try {
      final a = await WorldApi.agents();
      final f = await WorldApi.feed();
      if (!mounted) return;
      setState(() {
        _agents = a;
        _feed = f.reversed.toList();
      });
    } catch (_) {}
  }

  Map<String, dynamic>? _agentById(dynamic id) {
    for (final a in _agents) {
      if (a['id'] == id) return a as Map<String, dynamic>;
    }
    return null;
  }

  /// 居民没坐标时按所在地点落位,加索引抖动避免扎堆
  Offset _agentPos(Map<String, dynamic> a, int index) {
    final px = (a['posX'] as num?)?.toDouble();
    final py = (a['posY'] as num?)?.toDouble();
    if (px != null && py != null) return Offset(px, py);
    var fx = _worldW / 2;
    var fy = _worldH / 2;
    for (final p in _places) {
      if (p['key'] == a['location']) {
        fx = (p['x'] as num).toDouble();
        fy = (p['y'] as num).toDouble();
        break;
      }
    }
    return Offset(fx + (index % 3 - 1) * 24, fy + (index % 2) * 16 - 8);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final now = DateTime.now();
    const week = ['一', '二', '三', '四', '五', '六', '日'];
    return Scaffold(
      body: SafeArea(
        child: RefreshIndicator(
          onRefresh: _load,
          child: ListView(padding: const EdgeInsets.only(bottom: 16), children: [
            // 标题栏
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 4),
              child: Row(children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: Image.asset('assets/logo.png', width: 34, height: 34),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                    const Text('鹿匠笔记',
                        style: TextStyle(
                            fontSize: 20, fontWeight: FontWeight.bold)),
                    Text(
                        '${Auth.username ?? ''} · ${now.month}月${now.day}日 周${week[now.weekday - 1]}',
                        style: TextStyle(fontSize: 12, color: cs.outline)),
                  ]),
                ),
                // 固定入口:我的(导航带右滑也能到,这里保底可见)
                GestureDetector(
                  onTap: () => Navigator.of(context)
                      .push(MaterialPageRoute(builder: (_) => MeTab())),
                  child: CircleAvatar(
                    radius: 18,
                    backgroundColor: cs.primary.withValues(alpha: 0.12),
                    child: Icon(Icons.person_rounded,
                        color: cs.primary, size: 22),
                  ),
                ),
              ]),
            ),
            // 功能导航带
            SizedBox(
              height: 84,
              child: ListView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 12),
                children: [for (final f in _features) _NavButton(f)],
              ),
            ),
            // 小世界地图
            _SectionHeader('小世界', onMore: () => Navigator.of(context)
                .push(MaterialPageRoute(builder: (_) => const GameWorldPage()))),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: _mapCard(),
            ),
            // 实时活动
            _SectionHeader('实时活动', onMore: () => Navigator.of(context)
                .push(MaterialPageRoute(builder: (_) => const WorldTab()))),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: _feedCard(),
            ),
          ]),
        ),
      ),
    );
  }

  Widget _mapCard() {
    return LayoutBuilder(builder: (context, c) {
      final w = c.maxWidth;
      final h = w * _worldH / _worldW;
      return Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => Navigator.of(context)
              .push(MaterialPageRoute(builder: (_) => const GameWorldPage())),
          child: Container(
            width: w,
            height: h,
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Color(0xFFE9F4E2), Color(0xFFD9EDC9)]),
            ),
            child: Stack(children: [
              for (final p in _places)
                Positioned(
                  left: (p['x'] as num) / _worldW * w - 30,
                  top: (p['y'] as num) / _worldH * h - 16,
                  width: 60,
                  child: Column(children: [
                    Text('${p['emoji'] ?? ''}',
                        style: const TextStyle(fontSize: 17)),
                    Text('${p['name'] ?? ''}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                            fontSize: 8.5,
                            fontWeight: FontWeight.w600,
                            color: Color(0xFF4A5D3A))),
                  ]),
                ),
              for (var i = 0; i < _agents.length; i++)
                _agentMarker(_agents[i] as Map<String, dynamic>, i, w, h),
            ]),
          ),
        ),
      );
    });
  }

  Widget _agentMarker(Map<String, dynamic> a, int i, double w, double h) {
    final pos = _agentPos(a, i);
    return Positioned(
      left: pos.dx / _worldW * w - 11,
      top: pos.dy / _worldH * h - 30,
      child: ScaleTransition(
        scale: Tween(begin: 1.0, end: 1.15)
            .animate(CurvedAnimation(parent: _pulse, curve: Curves.easeInOut)),
        child: Container(
          width: 22,
          height: 22,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: Colors.white,
            shape: BoxShape.circle,
            border: Border.all(color: const Color(0xFF7A9B5E), width: 1.2),
            boxShadow: const [
              BoxShadow(color: Colors.black26, blurRadius: 2, offset: Offset(0, 1))
            ],
          ),
          child: Text(a['avatar'] as String? ?? '🙂',
              style: const TextStyle(fontSize: 11)),
        ),
      ),
    );
  }

  Widget _feedCard() {
    if (_feed.isEmpty) {
      return const Card(
        child: Padding(
          padding: EdgeInsets.all(20),
          child: Center(
              child: Text('小镇静悄悄的,居民们还没出门 🌙',
                  style: TextStyle(color: Colors.grey))),
        ),
      );
    }
    final items = _feed.take(8).toList();
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(children: [
          for (var i = 0; i < items.length; i++) ...[
            if (i > 0) const Divider(height: 1, indent: 52, endIndent: 16),
            _feedRow(items[i] as Map<String, dynamic>),
          ],
        ]),
      ),
    );
  }

  Widget _feedRow(Map<String, dynamic> f) {
    final agent = _agentById(f['agentId']);
    final isMemory = f['kind'] == 'memory';
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        CircleAvatar(
          radius: 15,
          backgroundColor: const Color(0xFFF3EDE4),
          child: Text(agent?['avatar'] as String? ?? '🙂',
              style: const TextStyle(fontSize: 14)),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(
              '${agent?['name'] ?? '居民'} ${f['content'] ?? ''}',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 13, height: 1.35),
            ),
            const SizedBox(height: 2),
            Text(
              '${isMemory ? '💭 想法' : '⚡ 行动'}'
              '${f['place'] != null ? ' · ${f['place']}' : ''}'
              ' · ${f['createdAt']?.toString().substring(11, 16) ?? ''}',
              style: TextStyle(fontSize: 10.5, color: Colors.grey.shade600),
            ),
          ]),
        ),
      ]),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader(this.title, {this.onMore});
  final String title;
  final VoidCallback? onMore;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 8),
      child: Row(children: [
        Text(title,
            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.bold)),
        const Spacer(),
        if (onMore != null)
          GestureDetector(
            onTap: onMore,
            child: Text('查看全部 ›',
                style: TextStyle(
                    fontSize: 12, color: Theme.of(context).colorScheme.primary)),
          ),
      ]),
    );
  }
}

class _Feature {
  final IconData icon;
  final String label;
  final Color color;
  final Widget page;
  const _Feature(this.icon, this.label, this.color, this.page);
}

class _NavButton extends StatelessWidget {
  const _NavButton(this.f);
  final _Feature f;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 6),
      child: GestureDetector(
        onTap: () => Navigator.of(context)
            .push(MaterialPageRoute(builder: (_) => f.page)),
        child: SizedBox(
          width: 56,
          child: Column(children: [
            Container(
              width: 50,
              height: 50,
              decoration: BoxDecoration(
                color: f.color.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(15),
              ),
              child: Icon(f.icon, color: f.color, size: 24),
            ),
            const SizedBox(height: 5),
            Text(f.label,
                style: const TextStyle(
                    fontSize: 11, fontWeight: FontWeight.w500)),
          ]),
        ),
      ),
    );
  }
}
