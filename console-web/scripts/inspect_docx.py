# -*- coding: utf-8 -*-
import io, os
from docx import Document
from docx.oxml.ns import qn
BASE = r"E:\学习\Mini-redis项目\tiering-kv\docs\thesis"
DOCX = os.path.join(BASE, "Tiering-KV-毕业论文(草稿).docx")
doc = Document(DOCX)
print("sections", len(doc.sections))
print("paragraphs", len(doc.paragraphs))
print("tables", len(doc.tables))
print("inline_shapes", len(doc.inline_shapes))
heads = [(p.style.name, p.text[:40]) for p in doc.paragraphs if p.style.name.startswith('Heading')]
print("headings", len(heads))
for h in heads[:8]:
    print("  ", h)
for i, s in enumerate(doc.sections):
    hdr = " | ".join(p.text for p in s.header.paragraphs).strip()
    ftr = " | ".join(p.text for p in s.footer.paragraphs).strip()
    pg = s._sectPr.find(qn('w:pgNumType'))
    fmt = pg.get(qn('w:fmt')) if pg is not None else None
    start = pg.get(qn('w:start')) if pg is not None else None
    print(f"SEC{i}: hdr={hdr[:30]!r} ftr={ftr[:20]!r} pgnum={fmt}/{start} firstpage={s.different_first_page_header_footer}")
print("first paragraphs:")
for p in doc.paragraphs[:6]:
    print("  ", p.style.name, "|", p.text[:40])
