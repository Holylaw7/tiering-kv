# -*- coding: utf-8 -*-
import io, os
from docx import Document
BASE = r"E:\学习\Mini-redis项目\tiering-kv\docs\thesis"
doc = Document(os.path.join(BASE, "Tiering-KV-毕业论文(草稿).docx"))
paras = [p for p in doc.paragraphs]
print("total paragraphs", len(paras))
# 找 TOC 区间
txt = "\n".join(p.text for p in paras)
i = txt.find("摘    要\t1")
print("TOC tail:", txt[i+1500:i+2100].replace("\n", " | ")[:600] if i>=0 else "not found")
# 封面字段
cover = [p.text.strip() for p in paras[:40] if p.text.strip()]
print("COVER:", cover[:14])
# 是否有模板说明残留
print("残留说明:", "请注意" in txt or "为方便论文编辑" in txt)
# 最后几段
tail = [p.text.strip() for p in paras[-8:] if p.text.strip()]
print("TAIL:", tail)
