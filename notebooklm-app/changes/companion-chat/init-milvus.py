#!/usr/bin/env python3
"""init-milvus.py — 初始化伴侣长期记忆的 Milvus collection.

集合名: companion_memories
字段: id (auto), embedding (FLOAT_VECTOR 1024), companion_id, user_id, memory_id, content
距离: IP (内积;与现有 nblm_chunks 风格一致)
已有则跳过;无则创建并建索引
默认连 127.0.0.1:19530(沿用 nblm_chunks 同实例),可通过环境变量覆盖

使用:
  pip install pymilvus
  python init-milvus.py
  MILVUS_HOST=milvus MILVUS_PORT=19530 python init-milvus.py
"""
import os
import sys

from pymilvus import connections, Collection, CollectionSchema, FieldSchema, DataType, utility

MILVUS_HOST = os.environ.get("MILVUS_HOST", "127.0.0.1")
MILVUS_PORT = int(os.environ.get("MILVUS_PORT", "19530"))
COLLECTION_NAME = "companion_memories"
DIM = 1024


def main() -> int:
    print("Connecting to Milvus at " + MILVUS_HOST + ":" + str(MILVUS_PORT) + " ...")
    connections.connect(alias="default", host=MILVUS_HOST, port=MILVUS_PORT)

    if utility.has_collection(COLLECTION_NAME):
        print("Collection '" + COLLECTION_NAME + "' already exists, skip creation.")
        coll = Collection(COLLECTION_NAME)
        coll.load()
        print("  loaded. num_entities=" + str(coll.num_entities))
        return 0

    fields = [
        FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
        FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=DIM),
        FieldSchema(name="companion_id", dtype=DataType.INT64),
        FieldSchema(name="user_id", dtype=DataType.VARCHAR, max_length=64),
        FieldSchema(name="memory_id", dtype=DataType.INT64),
        FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=512),
    ]
    schema = CollectionSchema(fields, description="AI 伴侣长期记忆(companion-chat)")
    coll = Collection(COLLECTION_NAME, schema=schema)

    # 与 nblm_chunks 风格一致: IVF_FLAT + IP, nlist=128
    index_params = {
        "metric_type": "IP",
        "index_type": "IVF_FLAT",
        "params": {"nlist": 128},
    }
    coll.create_index(field_name="embedding", index_params=index_params)
    coll.load()
    print("Created and loaded collection '" + COLLECTION_NAME + "' (dim=" + str(DIM) + ", metric=IP).")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        print("ERROR: " + str(e), file=sys.stderr)
        sys.exit(1)
