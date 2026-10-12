# The real files of tools/corpus/sheets/, made by Microsoft Excel through COM automation — a producer
# independent of the code that reads them — and, for each cell, what Excel SHOWS (`Range.Text`): the oracle
# the JVM tests compare the viewer with (SheetCorpusTest). Ported from the prototype's
# tools/xls-fixtures/make-fixtures.ps1 (347 cells of 347 on a phone), with the formats it did not make.
#
# Usage (Windows PowerShell 5.1, Excel installed, French regional settings as on the machine it was made on):
#     powershell -NoProfile -ExecutionPolicy Bypass -File tools/corpus/make_sheets.ps1 -Out tools/corpus/sheets
#
# Saved with a UTF-8 BOM: Windows PowerShell 5.1 reads a script without one as code page 1252. Formats are
# set as LOCAL codes (NumberFormatLocal, FormulaLocal): through PowerShell, `NumberFormat` is read as local
# too (measured: "dd/mm/yyyy" refused).
param([string]$Out)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)

# PowerShell's COM binder fixes the type of `Value2` at its first use (measured: Int32, then object[,]
# refused "as String"). Scalars: InvokeMember, which goes around it. Arrays: a plain assignment —
# InvokeMember refuses them (0x800A03EC, measured) — and the first use MUST be an array.
function SetV($range, $v) {
  if ($v -is [object[,]]) { $range.Value2 = $v }
  else { [void]$range.GetType().InvokeMember('Value2', [Reflection.BindingFlags]::SetProperty, $null, $range, @(,$v)) }
}

function Cell($ws, $addr, $value, $nfl) {
  $c = $ws.Range($addr)
  if ($nfl) { $c.NumberFormatLocal = $nfl }
  if ($value -is [string] -and $value.StartsWith('=')) { $c.FormulaLocal = $value }
  elseif ($value -is [string]) { SetV $c $value }
  else { SetV $c ([double]$value) }
}

function Dump($ws, $maxRows, $maxCols, $rowsFilter) {
  $cells = New-Object System.Collections.ArrayList
  for ($r = 1; $r -le $maxRows; $r++) {
    if ($rowsFilter -and -not ($rowsFilter -contains $r)) { continue }
    for ($c = 1; $c -le $maxCols; $c++) {
      $x = $ws.Cells.Item($r, $c)
      $v2 = $x.Value2
      if ($null -eq $v2 -and -not $x.MergeCells) { continue }
      $ma = $x.MergeArea
      [void]$cells.Add([ordered]@{
        r = $r - 1; c = $c - 1
        text = [string]$x.Text
        value2 = if ($null -eq $v2) { $null } else { [string]$v2 }
        type = if ($null -eq $v2) { 'null' } else { $v2.GetType().Name }
        nfl = [string]$x.NumberFormatLocal
        formula = [bool]$x.HasFormula
        merge = if ($x.MergeCells) { [string]$ma.Address($false, $false) } else { $null }
      })
    }
  }
  return ,$cells
}

function SaveAs($wb, $name, $format, $password) {
  $path = Join-Path $Out $name
  if (Test-Path $path) { Remove-Item $path }
  if ($password) { $wb.SaveAs($path, $format, $password) } else { $wb.SaveAs($path, $format) }
}

$xl = New-Object -ComObject Excel.Application
try {
  $xl.Visible = $false; $xl.DisplayAlerts = $false
  # Priming: the first `Value2` the binder sees is an array (see SetV), on a throwaway workbook.
  $prime = $xl.Workbooks.Add(); $a = New-Object 'object[,]' 1, 1; $a[0, 0] = 1.0
  $prime.Worksheets.Item(1).Range('A1').Value2 = $a; $prime.Close($false)
  $meta = [ordered]@{ producer = "Microsoft Excel $($xl.Version) build $($xl.Build)"; decimal = $xl.International(3); thousands = [int][char]$xl.International(4) }

  # ---------- the main workbook ----------
  $wb = $xl.Workbooks.Add()
  while ($wb.Worksheets.Count -lt 3) { [void]$wb.Worksheets.Add([Type]::Missing, $wb.Worksheets.Item($wb.Worksheets.Count)) }
  $s1 = $wb.Worksheets.Item(1); $s1.Name = 'Données été'
  $s2 = $wb.Worksheets.Item(2); $s2.Name = 'Ventes « 2026 »'
  $s3 = $wb.Worksheets.Item(3); $s3.Name = 'Vide'

  SetV ($s1.Range('A1')) 'Titre fusionné — Récapitulatif'
  [void]$s1.Range('A1:D1').Merge()
  Cell $s1 'A2' 'Libellé' $null; Cell $s1 'B2' 'Valeur' $null; Cell $s1 'C2' 'Commentaire' $null; Cell $s1 'D2' 'Après' $null
  $acc = '_-* # ##0,00 €_-;-* # ##0,00 €_-;_-* "-"?? €_-;_-@_-'
  $rows = @(
    @('Texte accentué', 'é è à ù ç œ Œ « » € ÿ — ok', '@'),
    @('Entier', 42, 'Standard'),
    @('Entier négatif', -1234567, '# ##0'),
    @('Décimal', 3.14159, '0,000'),
    @('Pourcentage', 0.125, '0,00%'),
    # A trap measured: in French codes, the space BEFORE % is the thousands separator => /1000 ("0,01%").
    @('Pourcentage espace', 0.125, '0,00 %'),
    @('Monétaire €', 1234.56, '# ##0,00 €'),
    @('Monétaire rouge', -1234.56, '# ##0,00 €;[Rouge]-# ##0,00 €'),
    @('Comptable +', 1234.56, $acc),
    @('Comptable -', -1234.56, $acc),
    @('Comptable 0', 0, $acc),
    @('Conditionnel', 1500, '[Bleu][>=1000]# ##0" k";[Rouge][<0]-0;0'),
    @('Date', 46304, 'jj/mm/aaaa'),
    @('Date longue', 46304, 'j mmmm aaaa'),
    @('Date et heure', 46304.607870370370, 'jj/mm/aaaa hh:mm:ss'),
    # 29/03/2026 02:30 does not exist in Paris (the spring change): the trap of going through java.util.Date.
    @('Trou heure été', 46110.104166666667, 'jj/mm/aaaa hh:mm:ss'),
    @('Heure', 0.6060185185185185, 'hh:mm'),
    @('Formule somme', '=SOMME(B4;B6)', '0,00'),
    @('Formule texte', '="Bonjour "&A3', $null),
    @('Vide au milieu', $null, $null),
    @('Booléen', '=VRAI', $null),
    @('Erreur', '=1/0', $null)
  )
  $r = 3
  $empty = 0
  foreach ($row in $rows) {
    Cell $s1 "A$r" $row[0] $null
    if ($null -ne $row[1]) { Cell $s1 "B$r" $row[1] $row[2] }
    if ($row[0] -eq 'Vide au milieu') { $empty = $r }
    $r++
  }
  # The "Vide au milieu" row: B and C empty, D filled.
  SetV ($s1.Range("D$empty")) 'après le vide'
  # A long text: 10 000 accented chars, past one SST record (8 224 bytes) => CONTINUE records in the .xls.
  $long = -join (1..1000 | ForEach-Object { 'éàœ€«»ç ok' })
  Cell $s1 "A$r" 'Texte long' $null; SetV ($s1.Range("B$r")) $long; $longRow = $r; $r++
  # A very wide row: 256 columns (A..IV, the limit of BIFF8).
  $wide = New-Object 'object[,]' 1, 256
  for ($c = 0; $c -lt 256; $c++) { $wide[0, $c] = "c$($c + 1)" }
  SetV ($s1.Range("A$r").Resize(1, 256)) $wide; $wideRow = $r

  # Sheet 2: a small table with relative formulas.
  Cell $s2 'A1' 'Date' $null; Cell $s2 'B1' 'Produit' $null; Cell $s2 'C1' 'Quantité' $null; Cell $s2 'D1' 'Prix unitaire' $null; Cell $s2 'E1' 'Total' $null
  $prod = @('Crème brûlée', 'Œuf à la coque', 'Façade été', 'Ñandú', 'Zürich')
  for ($i = 0; $i -lt 5; $i++) {
    $rr = $i + 2
    Cell $s2 "A$rr" (46300 + $i) 'jj/mm/aaaa'
    Cell $s2 "B$rr" $prod[$i] $null
    Cell $s2 "C$rr" ([double]($i + 1)) $null
    Cell $s2 "D$rr" (9.99 + $i * 10) '# ##0,00 €'
    Cell $s2 "E$rr" "=C$rr*D$rr" '# ##0,00 €'
  }
  Cell $s2 'D7' 'Total' $null; Cell $s2 'E7' '=SOMME(E2:E6)' '# ##0,00 €'

  foreach ($s in @($s1, $s2)) { [void]$s.UsedRange.Columns.AutoFit() }
  $s1.Columns.Item(2).ColumnWidth = 60

  $oracle = [ordered]@{
    meta = $meta
    file = 'excel-main'
    sheets = @(
      [ordered]@{ name = $s1.Name; cells = (Dump $s1 ($wideRow - 1) 4 $null) + (Dump $s1 $wideRow 256 @($wideRow)) },
      [ordered]@{ name = $s2.Name; cells = (Dump $s2 7 5 $null) },
      [ordered]@{ name = $s3.Name; cells = @() }
    )
    longRow = $longRow - 1; wideRow = $wideRow - 1; longLength = $long.Length
  }
  [IO.File]::WriteAllText((Join-Path $Out 'excel-main.expected.json'), ($oracle | ConvertTo-Json -Depth 6 -Compress), $utf8)

  SaveAs $wb 'excel-main.xls' 56 $null            # xlExcel8 (BIFF8)
  SaveAs $wb 'excel-main.xlsx' 51 $null           # xlOpenXMLWorkbook
  SaveAs $wb 'excel-main-strict.xlsx' 61 $null    # xlOpenXMLStrictWorkbook: the strict namespaces
  SaveAs $wb 'excel-main.ods' 60 $null            # xlOpenDocumentSpreadsheet
  SaveAs $wb 'excel-main-password.xls' 56 'Motdepasse1'
  SaveAs $wb 'excel-main-password.xlsx' 51 'Motdepasse1'
  try { SaveAs $wb 'excel-main-95.xls' 39 $null; 'excel95=ok' } catch { "excel95=refused: $($_.Exception.Message)" }  # xlExcel7
  $wb.Close($false)

  # ---------- the 1904 calendar ----------
  $wb = $xl.Workbooks.Add()
  $wb.Date1904 = $true
  $ws = $wb.Worksheets.Item(1); $ws.Name = 'Calendrier 1904'
  Cell $ws 'A1' 'Libellé' $null; Cell $ws 'B1' 'Valeur' $null
  $rows = @(
    @('Date', 44843, 'jj/mm/aaaa'),
    @('Date longue', 44843, 'j mmmm aaaa'),
    @('Date et heure', 44843.75, 'jj/mm/aaaa hh:mm'),
    # Three sections: never handed to CellFormat — the 1904 calendar must still apply (D-015).
    @('Date à sections', 44843, 'jj/mm/aaaa;"négatif";"zéro"'),
    @('Zéro à sections', 0, 'jj/mm/aaaa;"négatif";"zéro"'),
    @('Formule date', '=DATE(2026;10;12)', 'jj/mm/aaaa')
  )
  $r = 2
  foreach ($row in $rows) { Cell $ws "A$r" $row[0] $null; Cell $ws "B$r" $row[1] $row[2]; $r++ }
  [void]$ws.UsedRange.Columns.AutoFit()
  $oracle = [ordered]@{ meta = $meta; file = 'excel-1904'; sheets = @([ordered]@{ name = $ws.Name; cells = (Dump $ws ($r - 1) 2 $null) }) }
  [IO.File]::WriteAllText((Join-Path $Out 'excel-1904.expected.json'), ($oracle | ConvertTo-Json -Depth 6 -Compress), $utf8)
  SaveAs $wb 'excel-1904.xlsx' 51 $null
  SaveAs $wb 'excel-1904.xls' 56 $null
  $wb.Close($false)

  # ---------- 10 000 rows: past the 5 000 shown ----------
  $n = 10000
  $wb = $xl.Workbooks.Add()
  $ws = $wb.Worksheets.Item(1); $ws.Name = 'Gros'
  $data = New-Object 'object[,]' ($n + 1), 7
  $hdr = @('N°', 'Libellé', 'Date', 'Quantité', 'Prix', 'Remise', 'Catégorie')
  for ($c = 0; $c -lt 7; $c++) { $data[0, $c] = $hdr[$c] }
  $cats = @('Épicerie', 'Crèmerie', 'Boulangerie', 'Œnologie')
  for ($i = 1; $i -le $n; $i++) {
    $data[$i, 0] = $i
    $data[$i, 1] = "Article n°$i — été"
    $data[$i, 2] = 45658 + ($i % 365)
    $data[$i, 3] = ($i % 17) + 1
    $data[$i, 4] = [math]::Round(1.5 + ($i % 1000) / 7.0, 2)
    $data[$i, 5] = ($i % 20) / 100.0
    $data[$i, 6] = $cats[$i % 4]
  }
  SetV ($ws.Range('A1').Resize($n + 1, 7)) $data
  $ws.Range("C2:C$($n + 1)").NumberFormatLocal = 'jj/mm/aaaa'
  $ws.Range("E2:E$($n + 1)").NumberFormatLocal = '# ##0,00 €'
  $ws.Range("F2:F$($n + 1)").NumberFormatLocal = '0%'
  SetV ($ws.Range('H1')) 'Total'
  $ws.Range("H2:H$($n + 1)").FormulaLocal = '=D2*E2*(1-F2)'
  $ws.Range("H2:H$($n + 1)").NumberFormatLocal = '# ##0,00 €'
  [void]$ws.UsedRange.Columns.AutoFit()
  $sample = @(1, 2, 3, 5000, 5001, 9999, 10001)
  $oracle = [ordered]@{ meta = $meta; file = 'excel-10k'; rows = $n + 1; sampleRows = $sample
    sheets = @([ordered]@{ name = $ws.Name; cells = (Dump $ws ($n + 1) 8 $sample) }) }
  [IO.File]::WriteAllText((Join-Path $Out 'excel-10k.expected.json'), ($oracle | ConvertTo-Json -Depth 6 -Compress), $utf8)
  SaveAs $wb 'excel-10k.xlsx' 51 $null
  $wb.Close($false)
  $meta | ConvertTo-Json
} finally {
  $xl.Quit()
  [void][Runtime.InteropServices.Marshal]::ReleaseComObject($xl)
  [GC]::Collect(); [GC]::WaitForPendingFinalizers()
}
