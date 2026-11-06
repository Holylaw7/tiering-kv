# -*- coding: utf-8 -*-
import io, os, re
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.section import WD_SECTION
from docx.oxml.ns import qn
from docx.oxml import OxmlElement
from docx.text.paragraph import Paragraph

BASE = r"E:\学习\Mini-redis项目\tiering-kv\docs\thesis"
TEMPLATE = os.path.join(BASE, "_school", "本科生毕业论文（设计）电子模板.docx")
OUT = os.path.join(BASE, "Tiering-KV-毕业论文(草稿).docx")
TITLE = "面向冷热数据分层的 Redis 协议兼容 KV 存储引擎设计与实现"
BT = chr(96)
FENCE = BT * 3
SONG, HEI, TNR, MONO = "宋体", "黑体", "Times New Roman", "Consolas"

def set_run(run, name=SONG, size=12, bold=False, color=None):
    run.font.name = name
    run.font.size = Pt(size)
    run.font.bold = bold
    if color:
        run.font.color.rgb = RGBColor(*color)
    rPr = run._element.get_or_add_rPr()
    rFonts = rPr.find(qn('w:rFonts'))
    if rFonts is None:
        rFonts = OxmlElement('w:rFonts'); rPr.append(rFonts)
    for attr in ('w:eastAsia', 'w:ascii', 'w:hAnsi'):
        rFonts.set(qn(attr), name)

INLINE = re.compile(r'(\*\*.+?\*\*|' + BT + r'[^' + BT + r']+' + BT + r'|<sup>\[[^\]]+\]</sup>|\[[^\]]+\]\([^)]+\))')

def add_inline(p, text, name=SONG, size=12):
    for part in INLINE.split(text):
        if not part:
            continue
        if part.startswith('**') and part.endswith('**'):
            r = p.add_run(part[2:-2]); set_run(r, name, size, bold=True)
        elif part.startswith(BT) and part.endswith(BT) and len(part) > 2:
            r = p.add_run(part[1:-1]); set_run(r, MONO, size - 1.5)
        elif part.startswith('<sup>'):
            r = p.add_run(part[5:-6].replace('[', '').replace(']', ' ').strip())
            set_run(r, TNR, size - 2); r.font.superscript = True
        elif part.startswith('['):
            m = re.match(r'\[([^\]]+)\]\(([^)]+)\)', part)
            r = p.add_run(m.group(1) if m else part); set_run(r, name, size)
        else:
            r = p.add_run(part); set_run(r, name, size)

def ensure_heading_style(doc, level):
    name = 'Heading %d' % level
    try:
        doc.styles[name]
    except KeyError:
        from docx.enum.style import WD_STYLE_TYPE
        st = doc.styles.add_style(name, WD_STYLE_TYPE.PARAGRAPH)
        st.base_style = doc.styles['Normal']
        st.quick_style = True
    return name

def set_outline(p, level):
    pPr = p._p.get_or_add_pPr()
    ol = OxmlElement('w:outlineLvl'); ol.set(qn('w:val'), str(level - 1)); pPr.append(ol)

def heading(doc, text, level):
    size = {1: 15, 2: 14, 3: 12}[level]
    p = doc.add_paragraph(style=ensure_heading_style(doc, level))
    set_outline(p, level)
    p.paragraph_format.space_before = Pt(12 if level == 1 else 6)
    p.paragraph_format.space_after = Pt(6 if level == 1 else 3)
    p.paragraph_format.line_spacing = 1.0
    p.paragraph_format.first_line_indent = Pt(0)
    r = p.add_run(text); set_run(r, SONG, size, bold=True, color=(0, 0, 0))
    return p

def add_picture(doc, path, caption):
    p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(6); p.paragraph_format.space_after = Pt(0)
    p.add_run().add_picture(path, width=Cm(15))
    if caption:
        c = doc.add_paragraph(); c.alignment = WD_ALIGN_PARAGRAPH.CENTER
        c.paragraph_format.space_before = Pt(2); c.paragraph_format.space_after = Pt(10)
        r = c.add_run(caption); set_run(r, SONG, 10.5)

def add_table(doc, header, rows):
    t = doc.add_table(rows=1, cols=len(header))
    try:
        t.style = 'Table Grid'
    except KeyError:
        pass
    for i, h in enumerate(header):
        cell = t.rows[0].cells[i]
        cell.text = ""
        p = cell.paragraphs[0]; p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        r = p.add_run(h); set_run(r, SONG, 10.5, bold=True)
    for row in rows:
        cells = t.add_row().cells
        for i, v in enumerate(row[:len(header)]):
            cells[i].text = ""
            p = cells[i].paragraphs[0]
            p.paragraph_format.line_spacing = 1.0
            add_inline(p, v.replace('**', ''), SONG, 10.5)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)

def md_to_docx(doc, md_text, path_prefix, allow_h1=True):
    lines = md_text.split('\n')
    i = 0
    while i < len(lines):
        line = lines[i].rstrip()
        if not line.strip():
            i += 1; continue
        if line.startswith(FENCE):
            i += 1; buf = []
            while i < len(lines) and not lines[i].startswith(FENCE):
                buf.append(lines[i]); i += 1
            i += 1
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Cm(0.5); p.paragraph_format.line_spacing = 1.0
            p.paragraph_format.space_before = Pt(4); p.paragraph_format.space_after = Pt(4)
            r = p.add_run('\n'.join(buf)); set_run(r, MONO, 9)
            continue
        if line.startswith('# '):
            if allow_h1: heading(doc, line[2:].strip(), 1)
            i += 1; continue
        if line.startswith('## '):
            heading(doc, line[3:].strip(), 2); i += 1; continue
        if line.startswith('### '):
            heading(doc, line[4:].strip(), 3); i += 1; continue
        if line.startswith('---'):
            i += 1; continue
        m = re.match(r'!\[([^\]]*)\]\(([^)]+)\)', line)
        if m:
            img = os.path.normpath(os.path.join(path_prefix, m.group(2)))
            cap = ''
            if i + 2 < len(lines) and lines[i + 1].strip() == '' and lines[i + 2].startswith('*'):
                cap = lines[i + 2].strip().strip('*'); i += 3
            else:
                i += 1
            if os.path.exists(img):
                add_picture(doc, img, cap)
            continue
        if line.startswith('|'):
            block = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                block.append(lines[i].strip()); i += 1
            if len(block) >= 2 and re.match(r'^\|[\s:\-|]+\|$', block[1]):
                cells = [[c.strip() for c in r.strip('|').split('|')] for r in block]
                add_table(doc, cells[0], cells[2:])
            continue
        if line.startswith('> '):
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Pt(24); p.paragraph_format.right_indent = Pt(24)
            p.paragraph_format.line_spacing = 1.0
            p.paragraph_format.space_before = Pt(2); p.paragraph_format.space_after = Pt(2)
            add_inline(p, line[2:].strip(), SONG, 10.5)
            i += 1; continue
        if line.startswith('- '):
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Pt(24); p.paragraph_format.first_line_indent = Pt(-12)
            p.paragraph_format.line_spacing = 1.0
            p.paragraph_format.space_before = Pt(0); p.paragraph_format.space_after = Pt(0)
            add_inline(p, '· ' + line[2:].strip())
            i += 1; continue
        m = re.match(r'^(\d+)\.\s+(.*)$', line)
        if m:
            p = doc.add_paragraph()
            p.paragraph_format.left_indent = Pt(24); p.paragraph_format.first_line_indent = Pt(-12)
            p.paragraph_format.line_spacing = 1.0
            p.paragraph_format.space_before = Pt(0); p.paragraph_format.space_after = Pt(0)
            add_inline(p, m.group(1) + '. ' + m.group(2))
            i += 1; continue
        if line.startswith('*') and line.endswith('*') and len(line) > 2:
            p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
            r = p.add_run(line.strip('*')); set_run(r, SONG, 10.5)
            p.paragraph_format.space_after = Pt(8)
            i += 1; continue
        p = doc.add_paragraph()
        p.paragraph_format.first_line_indent = Pt(24)
        p.paragraph_format.line_spacing = 1.0
        p.paragraph_format.space_before = Pt(0); p.paragraph_format.space_after = Pt(0)
        add_inline(p, line.strip())
        i += 1

def field(p, instr):
    r = p.add_run()
    fld = OxmlElement('w:fldChar'); fld.set(qn('w:fldCharType'), 'begin'); r._r.append(fld)
    r2 = p.add_run()
    it = OxmlElement('w:instrText'); it.set(qn('xml:space'), 'preserve'); it.text = instr
    r2._r.append(it)
    r3 = p.add_run()
    f2 = OxmlElement('w:fldChar'); f2.set(qn('w:fldCharType'), 'separate'); r3._r.append(f2)
    r4 = p.add_run('（右键更新域）')
    r5 = p.add_run()
    f3 = OxmlElement('w:fldChar'); f3.set(qn('w:fldCharType'), 'end'); r5._r.append(f3)

def set_pgnum(section, fmt='decimal', start=None):
    sectPr = section._sectPr
    old = sectPr.find(qn('w:pgNumType'))
    if old is not None:
        sectPr.remove(old)
    pg = OxmlElement('w:pgNumType')
    pg.set(qn('w:fmt'), fmt)
    if start is not None:
        pg.set(qn('w:start'), str(start))
    # 按 schema 顺序插入：pgNumType 位于 pgMar 之后、cols 之前
    anchor = None
    for tag in ('w:pgMar', 'w:paperSrc', 'w:pgBorders', 'w:lnNumType'):
        el = sectPr.find(qn(tag))
        if el is not None:
            anchor = el
    if anchor is not None:
        anchor.addnext(pg)
    else:
        sectPr.append(pg)

doc = Document(TEMPLATE)
body = doc.element.body
paras = [c for c in body.iterchildren() if c.tag == qn('w:p')]
cut = None
for el in paras:
    if Paragraph(el, doc).text.strip().startswith('请注意'):
        cut = el; break
if cut is not None:
    removing = False
    for el in list(body.iterchildren()):
        if el is cut:
            removing = True
        if removing and el.tag != qn('w:sectPr'):
            body.remove(el)

toc_title = None
for el in body.iterchildren():
    if el.tag == qn('w:p') and Paragraph(el, doc).text.strip().replace(' ', '') == '目录':
        toc_title = el
if toc_title is None:
    raise SystemExit('未找到目录标题')
started = False
for el in list(body.iterchildren()):
    if el is toc_title:
        started = True; continue
    if started and el.tag == qn('w:p'):
        body.remove(el)
toc_p = OxmlElement('w:p')
toc_title.addnext(toc_p)
tp = Paragraph(toc_p, doc)
field(tp, ' TOC \\o "1-3" \\h \\z \\u ')
for r in tp.runs:
    set_run(r, SONG, 12)

toc_section = doc.sections[-1]
set_pgnum(toc_section, 'upperRoman', 1)
toc_section.footer.is_linked_to_previous = False

sec = doc.add_section(WD_SECTION.NEW_PAGE)
set_pgnum(sec, 'decimal', 1)
sec.footer.is_linked_to_previous = False
_fp = sec.footer.paragraphs[0]
_fp.alignment = WD_ALIGN_PARAGRAPH.CENTER
_r1 = _fp.add_run('第 '); set_run(_r1, SONG, 10.5)
field(_fp, ' PAGE ')
_r2 = _fp.add_run(' 页'); set_run(_r2, SONG, 10.5)
sec.different_first_page_header_footer = True
sec.header.is_linked_to_previous = False
hp = sec.header.paragraphs[0]
hp.alignment = WD_ALIGN_PARAGRAPH.CENTER
hr = hp.add_run(TITLE); set_run(hr, SONG, 10.5)
sec.first_page_header.is_linked_to_previous = False
_fh = sec.first_page_header.paragraphs[0]
_fh.text = ''

# 摘要
p = doc.add_paragraph(style=ensure_heading_style(doc, 1))
set_outline(p, 1)
p.paragraph_format.space_before = Pt(0); p.paragraph_format.space_after = Pt(6)
r = p.add_run('摘    要'); set_run(r, HEI, 10.5, bold=True)
front = io.open(os.path.join(BASE, 'front-matter.md'), encoding='utf-8').read()
cn = front.split('# Abstract')[0].split('# 摘要')[1]
for line in cn.split('\n'):
    s = line.strip()
    if not s: continue
    if s.startswith('关键词'):
        p = doc.add_paragraph(); p.paragraph_format.space_before = Pt(12)
        p.paragraph_format.first_line_indent = Pt(0)
        rb = p.add_run('关键词：'); set_run(rb, HEI, 10.5, bold=True)
        rr = p.add_run(s.split('：', 1)[1]); set_run(rr, SONG, 10.5)
    else:
        p = doc.add_paragraph(); p.paragraph_format.first_line_indent = Pt(21)
        p.paragraph_format.line_spacing = 1.0
        p.paragraph_format.left_indent = Pt(21); p.paragraph_format.right_indent = Pt(21)
        add_inline(p, s, SONG, 10.5)

doc.add_page_break()
p = doc.add_paragraph(style=ensure_heading_style(doc, 1))
set_outline(p, 1)
p.paragraph_format.space_before = Pt(0); p.paragraph_format.space_after = Pt(6)
r = p.add_run('Abstract'); set_run(r, TNR, 10.5, bold=True)
en = front.split('# Abstract')[1]
for line in en.split('\n'):
    s = line.strip()
    if not s: continue
    if s.startswith('Key words'):
        p = doc.add_paragraph(); p.paragraph_format.space_before = Pt(12)
        rb = p.add_run('Key words: '); set_run(rb, TNR, 10.5, bold=True)
        rr = p.add_run(s.split(':', 1)[1]); set_run(rr, TNR, 10.5)
    else:
        p = doc.add_paragraph(); p.paragraph_format.first_line_indent = Pt(21)
        p.paragraph_format.line_spacing = 1.0
        p.paragraph_format.left_indent = Pt(21); p.paragraph_format.right_indent = Pt(21)
        add_inline(p, s, TNR, 10.5)

doc.add_page_break()
# 前言
heading(doc, '前    言', 1)
pref = io.open(os.path.join(BASE, 'preface.md'), encoding='utf-8').read()
md_to_docx(doc, pref.split('# 前言')[1] if '# 前言' in pref else pref, BASE, allow_h1=False)

order = ['chapter1-introduction.md', 'chapter2-technology-and-design.md', 'chapter3-storage-engine.md',
         'chapter4-control-plane.md', 'chapter5-distributed.md', 'chapter6-testing-results.md',
         'chapter7-conclusion.md']
for f in order:
    doc.add_page_break()
    md_to_docx(doc, io.open(os.path.join(BASE, f), encoding='utf-8').read(), BASE, allow_h1=True)

doc.add_page_break()
heading(doc, '参考文献', 1)
refs = io.open(os.path.join(BASE, 'references-school.md'), encoding='utf-8').read()
for line in refs.split('\n'):
    s = line.strip()
    if not s or s.startswith('#') or s.startswith('>') or s.startswith('---'):
        continue
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Pt(21); p.paragraph_format.first_line_indent = Pt(-21)
    p.paragraph_format.line_spacing = 1.0
    add_inline(p, s, SONG, 10.5)

doc.add_page_break()
p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
r = p.add_run('致    谢'); set_run(r, SONG, 14, bold=True)
ack = io.open(os.path.join(BASE, 'acknowledgments.md'), encoding='utf-8').read()
for line in ack.split('\n'):
    s = line.strip()
    if not s or s.startswith('#'):
        continue
    p = doc.add_paragraph(); p.paragraph_format.first_line_indent = Pt(24)
    p.paragraph_format.line_spacing = 1.0
    add_inline(p, s, SONG, 12)

normal = doc.styles['Normal']
normal.font.name = SONG; normal.font.size = Pt(12)
normal.element.rPr.rFonts.set(qn('w:eastAsia'), SONG)

doc.save(OUT)
print('saved', OUT)