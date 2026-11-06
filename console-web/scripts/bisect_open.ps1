$ErrorActionPreference='Stop'
$word = New-Object -ComObject Word.Application
$word.Visible=$false
$d1 = $word.Documents.Open('E:\学习\Mini-redis项目\tiering-kv\docs\thesis\_school\min.docx')
'MIN_OK'
$d1.Close($false)
$d2 = $word.Documents.Open('E:\学习\Mini-redis项目\tiering-kv\docs\thesis\_school\本科生毕业论文（设计）电子模板.docx', $false, $true)
'TEMPLATE_OK'
$d2.Close($false)
$word.Quit()