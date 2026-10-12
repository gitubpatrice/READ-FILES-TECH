# The real files of tools/corpus/documents/, made by Word and PowerPoint through COM automation.
#
# Usage (Windows PowerShell 5.1, Office installed):
#     powershell -NoProfile -ExecutionPolicy Bypass -File tools/corpus/make_documents.ps1 -OutDir <folder> -Picture <a .png>
#
# Saved with a UTF-8 BOM: Windows PowerShell 5.1 reads a script without one as code page 1252, and the
# accents would reach Word as mojibake. Every argument of SaveAs/SaveAs2 is typed ([string], [int]):
# untyped, the call hung for minutes with no dialog (measured on Word 16.0 build 14334).
param([string]$OutDir, [string]$Picture)
$ErrorActionPreference = 'Stop'

$word = New-Object -ComObject Word.Application
try {
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $doc = $word.Documents.Add()
    $sel = $word.Selection

    $sel.Style = $doc.Styles.Item(-2)   # wdStyleHeading1
    $sel.TypeText("Rapport d'été — Année 2026")
    $sel.TypeParagraph()
    $sel.Style = $doc.Styles.Item(-1)   # wdStyleNormal
    $sel.TypeText("Été à la française : éàèçùôîïëœ — c'était parfait ! Ça coûte 12 € ; « guillemets ».")
    $doc.Footnotes.Add($sel.Range, [Type]::Missing, "Note de bas de page : source vérifiée.") | Out-Null
    $sel.TypeText(" Suite de la phrase après la note.")
    $sel.TypeParagraph()
    $sel.TypeText("Avant")
    $sel.TypeText([string][char]9)       # a tab
    $sel.TypeText("Après la tabulation")
    $sel.TypeText([string][char]11)      # a manual line break
    $sel.TypeText("Après le saut de ligne")
    $sel.TypeParagraph()

    $sel.Range.ListFormat.ApplyBulletDefault()
    $sel.TypeText("Premier élément")
    $sel.TypeParagraph()
    $sel.TypeText("Deuxième élément")
    $sel.TypeParagraph()
    $sel.TypeText("Troisième élément")
    $sel.TypeParagraph()
    $sel.Range.ListFormat.RemoveNumbers()

    $table = $doc.Tables.Add($sel.Range, 2, 3)
    $table.Borders.Enable = $true
    $cells = @(@("Nom", "Quantité", "Prix"), @("Pommes", "3", "2,50 €"))
    for ($r = 0; $r -lt 2; $r++) { for ($c = 0; $c -lt 3; $c++) { $table.Cell($r + 1, $c + 1).Range.Text = $cells[$r][$c] } }
    $sel.EndKey(6) | Out-Null           # wdStory
    $sel.TypeParagraph()

    $sel.InlineShapes.AddPicture($Picture) | Out-Null
    $sel.TypeParagraph()
    $box = $doc.Shapes.AddTextbox(1, 300, 400, 180, 60)   # msoTextOrientationHorizontal
    $box.TextFrame.TextRange.Text = "Texte dans une zone de texte"
    $sel.TypeText("Fin du document.")

    $doc.Sections.Item(1).Headers.Item(1).Range.Text = "En-tête du document"
    $doc.Sections.Item(1).Footers.Item(1).Range.Text = "Pied de page"

    $doc.SaveAs2([string](Join-Path $OutDir "word-report.docx"), [int]16)       # wdFormatXMLDocument
    $doc.SaveAs2([string](Join-Path $OutDir "word-report.odt"), [int]23)        # wdFormatOpenDocumentText
    $doc.SaveAs2([string](Join-Path $OutDir "word-report.doc"), [int]0)         # wdFormatDocument97
    $doc.SaveAs2([string](Join-Path $OutDir "word-report-password.docx"), [int]16, [bool]$false, [string]"corpus")
    $doc.Close(0)
} finally {
    $word.Quit()
}

$ppt = New-Object -ComObject PowerPoint.Application
try {
    $deck = $ppt.Presentations.Add([int]0)      # without a window
    $first = $deck.Slides.Add([int]1, [int]1)   # ppLayoutTitle
    $first.Shapes.Item(1).TextFrame.TextRange.Text = [string]"Présentation d'été"
    $first.Shapes.Item(2).TextFrame.TextRange.Text = [string]"Équipe de développement — octobre 2026"
    $second = $deck.Slides.Add([int]2, [int]2)  # ppLayoutText
    $second.Shapes.Item(1).TextFrame.TextRange.Text = [string]"Points clés"
    $second.Shapes.Item(2).TextFrame.TextRange.Text = [string]("Qualité du code" + [char]13 + "Sécurité des données" + [char]13 + "Café à volonté")
    $second.NotesPage.Shapes.Placeholders.Item(2).TextFrame.TextRange.Text = [string]"Notes de l'orateur : parler lentement."
    $deck.SaveAs([string](Join-Path $OutDir "powerpoint-slides.odp"), [int]35)   # ppSaveAsOpenDocumentPresentation
    $deck.Close()
} finally {
    $ppt.Quit()
}
