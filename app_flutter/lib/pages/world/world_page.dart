import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import '../../api/world_api.dart';
import '../../core/media.dart';

/// 小世界只读:居民 / 画廊 / 书籍 / 动态
class WorldPage extends StatelessWidget {
  const WorldPage({super.key});

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 4,
      child: Column(children: [
        const TabBar(tabs: [
          Tab(text: '居民'),
          Tab(text: '画廊'),
          Tab(text: '书籍'),
          Tab(text: '动态'),
        ]),
        Expanded(
          child: TabBarView(children: [
            _AgentsTab(),
            _GalleryTab(),
            _BooksTab(),
            _FeedTab(),
          ]),
        ),
      ]),
    );
  }
}

class _AgentsTab extends StatefulWidget {
  @override
  State<_AgentsTab> createState() => _AgentsTabState();
}

class _AgentsTabState extends State<_AgentsTab> {
  List<dynamic>? _list;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final l = await WorldApi.agents();
      if (mounted) setState(() => _list = l);
    } catch (_) {
      if (mounted) setState(() => _list = []);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_list == null) return const Center(child: CircularProgressIndicator());
    if (_list!.isEmpty) return const Center(child: Text('还没有居民'));
    return RefreshIndicator(
      onRefresh: _load,
      child: GridView.builder(
        padding: const EdgeInsets.all(12),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 3,
            childAspectRatio: 0.85,
            crossAxisSpacing: 8,
            mainAxisSpacing: 8),
        itemCount: _list!.length,
        itemBuilder: (_, i) {
          final a = _list![i];
          return Card(
            child: Padding(
              padding: const EdgeInsets.all(8),
              child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                Text(a['avatar'] as String? ?? '🙂',
                    style: const TextStyle(fontSize: 32)),
                const SizedBox(height: 6),
                Text(a['name'] as String? ?? '',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.bold)),
                Text(
                    '${a['moodEmoji'] ?? ''} ${a['occupation'] ?? a['title'] ?? ''}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11, color: Colors.grey)),
              ]),
            ),
          );
        },
      ),
    );
  }
}

class _GalleryTab extends StatefulWidget {
  @override
  State<_GalleryTab> createState() => _GalleryTabState();
}

class _GalleryTabState extends State<_GalleryTab> {
  final List<dynamic> _items = [];
  bool _loading = false;
  bool _hasMore = true;
  bool _firstLoaded = false;
  int _page = 0;

  @override
  void initState() {
    super.initState();
    _loadMore();
  }

  Future<void> _loadMore() async {
    if (_loading || !_hasMore) return;
    setState(() => _loading = true);
    try {
      final r = await WorldApi.gallery(page: _page);
      if (!mounted) return;
      setState(() {
        _items.addAll(r['items'] as List? ?? []);
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

  @override
  Widget build(BuildContext context) {
    if (!_firstLoaded) return const Center(child: CircularProgressIndicator());
    if (_items.isEmpty) return const Center(child: Text('还没有作品'));
    return NotificationListener<ScrollNotification>(
      onNotification: (n) {
        if (n.metrics.pixels > n.metrics.maxScrollExtent - 300) _loadMore();
        return false;
      },
      child: GridView.builder(
        padding: const EdgeInsets.all(8),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: 2,
            childAspectRatio: 1,
            crossAxisSpacing: 8,
            mainAxisSpacing: 8),
        itemCount: _items.length,
        itemBuilder: (_, i) {
          final card = _items[i];
          final p = card['product'] as Map<String, dynamic>? ?? {};
          final url = mediaUrl('/api/world/products/${p['id']}/media');
          return GestureDetector(
            onTap: () => Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => Scaffold(
                      appBar: AppBar(
                          title: Text(p['title'] as String? ?? '')),
                      body: Center(
                          child: InteractiveViewer(
                              child: CachedNetworkImage(imageUrl: url))),
                    ))),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: CachedNetworkImage(
                imageUrl: url,
                fit: BoxFit.cover,
                placeholder: (_, _) => Container(
                    color: Colors.grey.shade200,
                    child: const Center(
                        child: CircularProgressIndicator(strokeWidth: 2))),
                errorWidget: (_, _, _) => Container(
                    color: Colors.grey.shade200,
                    child: const Icon(Icons.broken_image_outlined)),
              ),
            ),
          );
        },
      ),
    );
  }
}

class _BooksTab extends StatefulWidget {
  @override
  State<_BooksTab> createState() => _BooksTabState();
}

class _BooksTabState extends State<_BooksTab> {
  List<dynamic>? _list;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final l = await WorldApi.books();
      if (mounted) setState(() => _list = l);
    } catch (_) {
      if (mounted) setState(() => _list = []);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_list == null) return const Center(child: CircularProgressIndicator());
    if (_list!.isEmpty) return const Center(child: Text('还没有连载'));
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.builder(
        itemCount: _list!.length,
        itemBuilder: (_, i) {
          final b = _list![i];
          return ListTile(
            leading: CircleAvatar(
                child: Text(b['authorAvatar'] as String? ?? '📖')),
            title: Text(b['latestTitle'] as String? ?? '',
                maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle:
                Text('${b['authorName'] ?? ''} · ${b['chapterCount'] ?? 0} 章'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => Navigator.of(context).push(MaterialPageRoute(
                builder: (_) => _BookReaderPage(
                    agentId: b['agentId'] as int,
                    authorName: b['authorName'] as String? ?? ''))),
          );
        },
      ),
    );
  }
}

class _BookReaderPage extends StatefulWidget {
  const _BookReaderPage({required this.agentId, required this.authorName});
  final int agentId;
  final String authorName;

  @override
  State<_BookReaderPage> createState() => _BookReaderPageState();
}

class _BookReaderPageState extends State<_BookReaderPage> {
  Map<String, dynamic>? _book;
  int _chapter = 0;

  @override
  void initState() {
    super.initState();
    WorldApi.book(widget.agentId).then((b) {
      if (mounted) setState(() => _book = b);
    }).catchError((_) {
      if (mounted) setState(() => _book = {'chapters': []});
    });
  }

  @override
  Widget build(BuildContext context) {
    final b = _book;
    if (b == null) {
      return Scaffold(
          appBar: AppBar(title: Text(widget.authorName)),
          body: const Center(child: CircularProgressIndicator()));
    }
    final chapters = (b['chapters'] as List?) ?? [];
    if (chapters.isEmpty) {
      return Scaffold(
          appBar: AppBar(title: Text(widget.authorName)),
          body: const Center(child: Text('没有章节')));
    }
    final ch = chapters[_chapter];
    return Scaffold(
      appBar: AppBar(
        title: Text(ch['title'] as String? ?? '第 ${_chapter + 1} 章'),
        actions: [
          IconButton(
              icon: const Icon(Icons.chevron_left),
              onPressed:
                  _chapter > 0 ? () => setState(() => _chapter--) : null),
          Text('${_chapter + 1}/${chapters.length}'),
          IconButton(
              icon: const Icon(Icons.chevron_right),
              onPressed: _chapter < chapters.length - 1
                  ? () => setState(() => _chapter++)
                  : null),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Text(ch['content'] as String? ?? ''),
      ),
    );
  }
}

class _FeedTab extends StatefulWidget {
  @override
  State<_FeedTab> createState() => _FeedTabState();
}

class _FeedTabState extends State<_FeedTab> {
  List<dynamic>? _list;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final l = await WorldApi.feed();
      if (mounted) setState(() => _list = l.reversed.toList());
    } catch (_) {
      if (mounted) setState(() => _list = []);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_list == null) return const Center(child: CircularProgressIndicator());
    if (_list!.isEmpty) return const Center(child: Text('最近 10 分钟没有动态'));
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView.builder(
        itemCount: _list!.length,
        itemBuilder: (_, i) {
          final f = _list![i];
          final isMemory = f['kind'] == 'memory';
          return ListTile(
            dense: true,
            leading: Icon(
                isMemory ? Icons.psychology_outlined : Icons.bolt_outlined,
                size: 20),
            title: Text(f['content'] as String? ?? '',
                maxLines: 2, overflow: TextOverflow.ellipsis),
            subtitle: Text(
                '${f['type'] ?? ''}${f['place'] != null ? ' · ${f['place']}' : ''} · ${f['createdAt']?.toString().substring(11, 16) ?? ''}'),
          );
        },
      ),
    );
  }
}
