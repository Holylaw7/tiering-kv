# -*- coding: utf-8 -*-
import io
from docx import Document
from docx.oxml.ns import qn
base = r"E:\学习\Mini-redis项目\tiering-kv\docs\thesis\_school"
doc = Document(base + r"\本科生毕业论文（设计）电子模板.docx")
out = []
body = doc.element.body
pi = 0
for child in body.iterchildren():
    tag = child.tag.split('}')[1]
    if tag == 'p':
        from docx.text.paragraph import Paragraph
        p = Paragraph(child, doc)
        txt = p.text.strip()
        out.append(f"P{pi:03d} | {p.style.name} | {txt[:60]}")
        pi += 1
    elif tag == 'sectPr':
        out.append("SECTPR")
    else:
        out.append("OTHER:" + tag)
with io.open(base + r"\template_index.txt", "w", encoding="utf-8") as f:
    f.write("\n".join(out))
print("paras", pi, "lines", len(out))
