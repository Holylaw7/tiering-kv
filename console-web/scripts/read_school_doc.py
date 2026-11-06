# -*- coding: utf-8 -*-
import io
from docx import Document
base = r"E:\学习\Mini-redis项目\tiering-kv\docs\thesis\_school"
spec = Document(base + r"\本科生毕业论文（设计）撰写规范(修改版).docx")
with io.open(base + r"\spec.txt", "w", encoding="utf-8") as f:
    for p in spec.paragraphs:
        t = p.text.rstrip()
        if t:
            f.write(t + "\n")

tpl = Document(base + r"\本科生毕业论文（设计）电子模板.docx")
with io.open(base + r"\template.txt", "w", encoding="utf-8") as f:
    f.write("=== SECTIONS ===\n")
    for i, s in enumerate(tpl.sections):
        f.write(f"[{i}] page {s.page_width.cm:.2f}x{s.page_height.cm:.2f}cm margins L{s.left_margin.cm:.2f} R{s.right_margin.cm:.2f} T{s.top_margin.cm:.2f} B{s.bottom_margin.cm:.2f} header {s.header_distance.cm:.2f} footer {s.footer_distance.cm:.2f}\n")
    f.write("=== PARAGRAPHS ===\n")
    for i, p in enumerate(tpl.paragraphs):
        t = p.text.strip()
        if not t:
            continue
        sizes = set()
        fonts = set()
        for r in p.runs:
            if r.font.size:
                sizes.add(round(r.font.size.pt, 1))
            if r.font.name:
                fonts.add(r.font.name)
        f.write(f"[{i}] style={p.style.name} | align={p.alignment} | size={sorted(sizes)} | font={sorted(fonts)} | {t[:80]}\n")
    f.write("=== TABLES ===\n")
    for ti, tb in enumerate(tpl.tables):
        f.write(f"table {ti}: rows={len(tb.rows)} cols={len(tb.columns)}\n")
        for r in tb.rows[:4]:
            f.write("  | " + " | ".join(c.text.strip().replace("\n", " ")[:24] for c in r.cells) + "\n")
print("done")
