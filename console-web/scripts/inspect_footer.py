# -*- coding: utf-8 -*-
import zipfile, re, os
base = r"E:\学习\Mini-redis项目\tiering-kv\docs\thesis"
for name, path in [("TEMPLATE", os.path.join(base, "_school", "本科生毕业论文（设计）电子模板.docx")),
                   ("OURS", os.path.join(base, "Tiering-KV-毕业论文(草稿).docx"))]:
    z = zipfile.ZipFile(path)
    footers = [n for n in z.namelist() if n.startswith('word/footer')]
    print(name, "footers:", footers)
    for f in footers[:3]:
        xml = z.read(f).decode('utf-8')
        txt = re.sub(r'<[^>]+>', '', xml)
        print("  ", f, "text=", txt.strip()[:60], "| has PAGE field:", 'PAGE' in xml)
    doc = z.read('word/document.xml').decode('utf-8')
    for m in re.finditer(r'<w:pgNumType[^/]*/>', doc):
        print("   pgNumType:", m.group(0))
