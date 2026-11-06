$ErrorActionPreference='Stop'
$word = New-Object -ComObject Word.Application
$word.Visible=$false; $word.DisplayAlerts=0
$doc = $word.Documents.Open('E:\学习\Mini-redis项目\tiering-kv\docs\thesis\Tiering-KV-毕业论文(草稿).docx', $false, $true)
'OPENED pages=' + $doc.ComputeStatistics(2)
foreach ($i in 1..$doc.Sections.Count) { $s = $doc.Sections.Item($i); 'SEC' + $i + ' ftr=[' + $s.Footers.Item(1).Range.Text.Trim() + '] hdr=[' + $s.Headers.Item(1).Range.Text.Trim() + ']' }
$doc.Close($false); $word.Quit(); 'OK'