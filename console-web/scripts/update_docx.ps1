$ErrorActionPreference='Stop'
$docx = 'E:\学习\Mini-redis项目\tiering-kv\docs\thesis\Tiering-KV-毕业论文(草稿).docx'
$pdf  = 'E:\学习\Mini-redis项目\tiering-kv\docs\thesis\Tiering-KV-毕业论文(草稿).pdf'
$word = New-Object -ComObject Word.Application
$word.Visible=$false; $word.DisplayAlerts=0
$doc = $word.Documents.Open($docx)
$doc.Fields.Update() | Out-Null
foreach ($toc in $doc.TablesOfContents) { $toc.Update() }
$doc.Repaginate()
'PAGES=' + $doc.ComputeStatistics(2)
'TOC_COUNT=' + $doc.TablesOfContents.Count
if ($doc.TablesOfContents.Count -gt 0) { $t = $doc.TablesOfContents.Item(1).Range.Text; 'TOC_HEAD=' + $t.Substring(0, [Math]::Min(150, $t.Length)).Replace("`r", ' / ') }
'INLINE_SHAPES=' + $doc.InlineShapes.Count
'TABLES=' + $doc.Tables.Count
foreach ($i in 1..$doc.Sections.Count) { $s = $doc.Sections.Item($i); 'SEC' + $i + ' hdr=[' + $s.Headers.Item(1).Range.Text.Trim() + '] ftr=[' + $s.Footers.Item(1).Range.Text.Trim() + ']' }
$doc.Save(); $doc.ExportAsFixedFormat($pdf, 17); $doc.Close($false); $word.Quit(); 'PDF_OK'