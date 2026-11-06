$ErrorActionPreference='Stop'
$docx='E:\学习\Mini-redis项目\tiering-kv\docs\thesis\Tiering-KV-毕业论文(草稿).docx'
$pdf='E:\学习\Mini-redis项目\tiering-kv\docs\thesis\Tiering-KV-毕业论文(草稿).pdf'
$word=New-Object -ComObject Word.Application
$word.Visible=$false; $word.DisplayAlerts=0
$doc=$word.Documents.Open($docx)
$doc.TablesOfContents.Item(1).Update()
$doc.Repaginate()
'PAGES=' + $doc.ComputeStatistics(2)
$t=$doc.TablesOfContents.Item(1).Range.Text
'TOC_LEN=' + $t.Length
'TOC_HEAD=' + $t.Substring(0,[Math]::Min(200,$t.Length)).Replace([char]13,'|')
'IMAGES=' + $doc.InlineShapes.Count + ' TABLES=' + $doc.Tables.Count
$doc.Save()
$doc.ExportAsFixedFormat($pdf,17)
$doc.Close($false); $word.Quit(); 'DONE'