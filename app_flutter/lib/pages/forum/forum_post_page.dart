import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import '../../api/forum_api.dart';
import '../../core/media.dart';

/// 论坛帖子详情:正文 + 附件图 + 点赞收藏 + 评论
class ForumPostPage extends StatefulWidget {
  const ForumPostPage({super.key, required this.postId});
  final int postId;

  @override
  State<ForumPostPage> createState() => _ForumPostPageState();
}

class _ForumPostPageState extends State<ForumPostPage> {
  Map<String, dynamic>? _detail;
  final List<dynamic> _comments = [];
  bool _commentsLoaded = false;
  bool _liked = false;
  bool _favorited = false;
  bool _busy = false;
  final _commentCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _commentCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final d = await ForumApi.post(widget.postId);
      final c = await ForumApi.comments(widget.postId);
      if (!mounted) return;
      setState(() {
        _detail = d;
        _comments
          ..clear()
          ..addAll(c['comments'] as List? ?? []);
        _commentsLoaded = true;
        final cu = d['currentUser'] as Map? ?? {};
        _liked = cu['liked'] as bool? ?? false;
        _favorited = cu['favorited'] as bool? ?? false;
      });
    } catch (_) {
      if (mounted) setState(() => _commentsLoaded = true);
    }
  }

  Future<void> _toggle(bool isLike) async {
    if (_busy) return;
    setState(() => _busy = true);
    final was = isLike ? _liked : _favorited;
    setState(() {
      if (isLike) {
        _liked = !was;
      } else {
        _favorited = !was;
      }
      final post = _detail?['post'] as Map<String, dynamic>?;
      if (post != null) {
        final key = isLike ? 'likeCount' : 'favoriteCount';
        post[key] = ((post[key] as num?) ?? 0) + (was ? -1 : 1);
      }
    });
    try {
      if (isLike) {
        await ForumApi.like(widget.postId, !was);
      } else {
        await ForumApi.favorite(widget.postId, !was);
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          if (isLike) {
            _liked = was;
          } else {
            _favorited = was;
          }
        });
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('操作失败')));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _sendComment() async {
    final text = _commentCtrl.text.trim();
    if (text.isEmpty) return;
    try {
      final c = await ForumApi.addComment(widget.postId, text);
      if (!mounted) return;
      _commentCtrl.clear();
      setState(() => _comments.add(c));
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('评论失败')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final d = _detail;
    return Scaffold(
      appBar: AppBar(title: const Text('帖子')),
      body: d == null
          ? const Center(child: CircularProgressIndicator())
          : _body(d),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: EdgeInsets.only(
              left: 12,
              right: 12,
              bottom: MediaQuery.of(context).viewInsets.bottom + 8,
              top: 4),
          child: Row(children: [
            Expanded(
              child: TextField(
                controller: _commentCtrl,
                decoration: const InputDecoration(
                    hintText: '写评论…', border: OutlineInputBorder(),
                    isDense: true),
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filled(
                onPressed: _sendComment, icon: const Icon(Icons.send)),
          ]),
        ),
      ),
    );
  }

  Widget _body(Map<String, dynamic> d) {
    final p = d['post'] as Map<String, dynamic>? ?? {};
    final atts = (d['attachments'] as List?) ?? [];
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(children: [
          CircleAvatar(
              child: Text(p['authorAvatar'] as String? ?? '🙂')),
          const SizedBox(width: 8),
          Expanded(
              child: Text(p['authorName'] as String? ?? '',
                  style: const TextStyle(fontWeight: FontWeight.bold))),
          Text(p['createdAt']?.toString().substring(0, 10) ?? '',
              style: const TextStyle(fontSize: 11, color: Colors.grey)),
        ]),
        const SizedBox(height: 12),
        Text(p['title'] as String? ?? '',
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
        const SizedBox(height: 8),
        Text(p['content'] as String? ?? ''),
        for (final a in atts.where(
            (x) => (x['mediaType'] as String? ?? '').startsWith('image')))
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: CachedNetworkImage(
                imageUrl: mediaUrl(
                    '/api/world/forum/attachments/${a['id']}/media'),
                fit: BoxFit.cover,
                errorWidget: (_, _, _) => const SizedBox.shrink(),
              ),
            ),
          ),
        const SizedBox(height: 12),
        Row(children: [
          TextButton.icon(
            onPressed: () => _toggle(true),
            icon: Icon(_liked ? Icons.favorite : Icons.favorite_border,
                color: _liked ? Colors.red : null),
            label: Text('${p['likeCount'] ?? 0}'),
          ),
          TextButton.icon(
            onPressed: () => _toggle(false),
            icon: Icon(_favorited ? Icons.star : Icons.star_border,
                color: _favorited ? Colors.amber : null),
            label: Text('${p['favoriteCount'] ?? 0}'),
          ),
        ]),
        const Divider(),
        Text('评论 ${d['totalComments'] ?? _comments.length}',
            style: const TextStyle(fontWeight: FontWeight.bold)),
        if (!_commentsLoaded)
          const Padding(
              padding: EdgeInsets.all(16),
              child: Center(child: CircularProgressIndicator())),
        for (final c in _comments)
          ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.chat_bubble_outline, size: 18),
            title: Text(c['authorName'] as String? ?? '',
                style: const TextStyle(fontSize: 13)),
            subtitle: Text(c['content'] as String? ?? ''),
          ),
      ],
    );
  }
}
