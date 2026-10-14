' CSVs written by Excel itself (COM), for the CSV editor's oracle: one sheet saved in six of Excel's
' CSV formats. Usage: cscript //nologo make_excel_csv.vbs <absolute path of tools/corpus/csv-editor>
' PowerShell is refused to the agents: VBScript drives Excel instead. ASCII only (the accents by ChrW):
' cscript reads a script in the ANSI code page, and the repository keeps it in UTF-8.
Option Explicit

Dim folder, excel, book, sheet
folder = WScript.Arguments(0)
Set excel = CreateObject("Excel.Application")
excel.DisplayAlerts = False
excel.Visible = False

Sub Fill(sheet)
    sheet.Range("A1:E1").Value = Array("Nom", "Ville", "Montant", "Remarque", "Code postal")
    sheet.Range("A2:C2").Value = Array(ChrW(201) & "mile Zola", "Paris", 1234.56)
    sheet.Range("D2").Value = "Ligne 1" & vbLf & "Ligne 2"
    sheet.Range("E2").NumberFormat = "@"
    sheet.Range("E2").Value = "01000"
    sheet.Range("A3:C3").Value = Array("albert", "Lyon; Rh" & ChrW(244) & "ne, sud", -5)
    sheet.Range("D3").Value = "Il a dit ""bonjour"""
    sheet.Range("A4").Value = "Zo" & ChrW(233)
    sheet.Range("B4").NumberFormat = "@"
    sheet.Range("B4").Value = "+33 6 12 34 56 78"
    sheet.Range("D4").NumberFormat = "@"
    sheet.Range("D4").Value = "=A1 written as text"
    ' Row 5 left empty inside the used range: Excel writes it as a row of separators.
    sheet.Range("A6:C6").Value = Array("Bob", ChrW(201) & "vry", 0.25)
End Sub

Sub SaveAs(name, format, isLocal)
    Set book = excel.Workbooks.Add
    Set sheet = book.Worksheets(1)
    Fill sheet
    book.SaveAs folder & "\" & name, format, , , , , , , , , , isLocal
    book.Close False
End Sub

SaveAs "excel-xlcsv-local.csv", 6, True
SaveAs "excel-xlcsv-comma.csv", 6, False
SaveAs "excel-utf8-local.csv", 62, True
SaveAs "excel-mac-cr.csv", 22, True
SaveAs "excel-msdos.csv", 24, True
SaveAs "excel-unicode-tab.csv", 42, True

excel.Quit
