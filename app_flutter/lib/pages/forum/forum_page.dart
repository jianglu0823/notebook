import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import '../../api/forum_api.dart';
import '../../core/media.dart';
import 'forum_post_page.dart';

/// 论坛 feed:分类 chips + 分页帖子卡片
class ForumPage extends StatefulWidget {
  const ForumPage({super.key});

  @override
  State<ForumPage> createState() => _ForumPageState();
}

class _ForumPageState extends State<ForumPage> {
  List<dynamic> _cats = [];
  String? _category; // null = 全部
  final List<dynamic> _posts = [];
  bool _loading = false;
  bool _hasMore = true;
  int _page = 0;
  bool _firstLoaded = false;
  final _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _loadCats();
    _loadMore();
    _scroll.addListener(() {
      if (_scroll.position.pixels >
          _scroll.position.maxScrollExtent - 300) {
        _loadMore();
      }
    });
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  static String? _normCode(dynamic code) {
    final s = code as String? ?? '';
    return s.isEmpty ? null : s;
  }

  Future<void> _loadCats() async {
    try {
      final c = await ForumApi.categories();
      if (mounted) setState(() => _cats = c);
    } catch (_) {}
  }

  Future<void> _loadMore() async {
    if (_loading || !_hasMore) return;
    setState(() => _loading = true);
    try {
      final r =
          await ForumApi.posts(category: _category, page: _page, size: 20);
      if (!mounted) return;
      setState(() {
        _posts.addAll(r['posts'] as List? ?? []);
        _hasMore = r['hasMore'] as bool? ?? false;
        _page++;
        _firstLoaded = true;
      });
    } catch (_) {
      if (mounted) setState(() => _firstLoaded = true);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _refresh() async {
    setState(() {
      _posts.clear();
      _page = 0;
      _hasMore = true;
    });
    await _loadMore();
  }

  @override
  Widget build(BuildContext context) {
    return Column(children: [
      if (_cats.isNotEmpty)
        SizedBox(
          height: 44,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            children: [
              for (final c in _cats)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ChoiceChip(
                    label: Text('${c['label']}'),
                    // 后端「全部」的 code 是空串,归一化为 null
                    selected: _category == _normCode(c['code']),
                    onSelected: (_) {
                      setState(() => _category = _normCode(c['code']));
                      _refresh();
                    },
                  ),
                ),
            ],
          ),
        ),
      Expanded(
        child: !_firstLoaded
            ? const Center(child: CircularProgressIndicator())
            : _posts.isEmpty
                ? const Center(child: Text('还没有帖子'))
                : RefreshIndicator(
                    onRefresh: _refresh,
                    child: ListView.builder(
                      controller: _scroll,
                      itemCount: _posts.length + (_hasMore ? 1 : 0),
                      itemBuilder: (_, i) {
                        if (i >= _posts.length) {
                          return const Padding(
                            padding: EdgeInsets.all(16),
                            child: Center(
                                child: CircularProgressIndicator()),
                          );
                        }
                        return _card(_posts[i]);
                      },
                    ),
                  ),
      ),
    ]);
  }

  Widget _card(Map<String, dynamic> p) {
    final atts = (p['attachments'] as List?) ?? [];
    final img = atts.firstWhere(
        (a) => (a['mediaType'] as String? ?? '').startsWith('image'),
        orElse: () => null);
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => Navigator.of(context).push(MaterialPageRoute(
            builder: (_) => ForumPostPage(postId: p['id'] as int))),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              CircleAvatar(
                  radius: 14,
                  child: Text(p['authorAvatar'] as String? ?? '🙂',
                      style: const TextStyle(fontSize: 14))),
              const SizedBox(width: 8),
              Expanded(
                  child: Text(p['authorName'] as String? ?? '',
                      maxLines: 1, overflow: TextOverflow.ellipsis)),
              Text(p['createdAt']?.toString().substring(0, 10) ?? '',
                  style: const TextStyle(fontSize: 11, color: Colors.grey)),
            ]),
            const SizedBox(height: 8),
            Text(p['title'] as String? ?? '',
                style: const TextStyle(
                    fontSize: 15, fontWeight: FontWeight.bold)),
            const SizedBox(height: 4),
            Text(p['content'] as String? ?? '',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: Colors.black87)),
            if (img != null) ...[
              const SizedBox(height: 8),
              ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: CachedNetworkImage(
                  imageUrl: mediaUrl(
                      '/api/world/forum/attachments/${img['id']}/media'),
                  height: 160,
                  width: double.infinity,
                  fit: BoxFit.cover,
                  errorWidget: (_, _, _) => const SizedBox.shrink(),
                ),
              ),
            ],
            const SizedBox(height: 8),
            Row(children: [
              _count(Icons.favorite_border, p['likeCount']),
              const SizedBox(width: 16),
              _count(Icons.star_border, p['favoriteCount']),
              const SizedBox(width: 16),
              _count(Icons.chat_bubble_outline, p['commentCount']),
            ]),
          ]),
        ),
      ),
    );
  }

  Widget _count(IconData icon, dynamic n) {
    return Row(mainAxisSize: MainAxisSize.min, children: [
      Icon(icon, size: 16, color: Colors.grey),
      const SizedBox(width: 4),
      Text('${n ?? 0}',
          style: const TextStyle(fontSize: 12, color: Colors.grey)),
    ]);
  }
}
