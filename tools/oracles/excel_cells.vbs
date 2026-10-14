' Excel's own reading of a workbook, for convert_oracle.py (phase 5, Convert: CSV -> XLSX).
' Usage: cscript //nologo excel_cells.vbs <workbook.xlsx> <out.txt>
' Writes, in UTF-8, one line per non-empty cell of the first sheet: row TAB column TAB kind TAB value, where
' kind is "s" (text: its UTF-16 code units in hex, four digits each) or "n" (a number: its Formula, the
' value as Excel writes it in English). The first line gives the sheet count. Exit code 2: Excel refused
' to open the file.
Option Explicit

Dim excel, book, sheet, used, r, c, cell, v, out, lastRow, lastColumn
Set excel = CreateObject("Excel.Application")
excel.Visible = False
excel.DisplayAlerts = False
On Error Resume Next
Set book = excel.Workbooks.Open(WScript.Arguments(0), 0, True)
If Err.Number <> 0 Then
    WScript.StdErr.WriteLine "OPEN FAILED: " & Err.Description
    excel.Quit
    WScript.Quit 2
End If
On Error GoTo 0

Set sheet = book.Worksheets(1)
Set used = sheet.UsedRange
lastRow = used.Row + used.Rows.Count - 1
lastColumn = used.Column + used.Columns.Count - 1
Set out = CreateObject("ADODB.Stream")
out.Type = 2
out.Charset = "utf-8"
out.Open
out.WriteText "sheets" & vbTab & book.Worksheets.Count & vbLf
For r = 1 To lastRow
    For c = 1 To lastColumn
        Set cell = sheet.Cells(r, c)
        v = cell.Value2
        If Not IsEmpty(v) Then
            If VarType(v) = vbString Then
                out.WriteText r & vbTab & c & vbTab & "s" & vbTab & Units(v) & vbLf
            Else
                out.WriteText r & vbTab & c & vbTab & "n" & vbTab & cell.Formula & vbLf
            End If
        End If
    Next
Next
out.SaveToFile WScript.Arguments(1), 2
out.Close
book.Close False
excel.Quit

' The UTF-16 code units of s, four hex digits each: what the cell holds, line breaks and all.
Function Units(s)
    Dim k, h
    h = ""
    For k = 1 To Len(s)
        h = h & Right("000" & Hex(AscW(Mid(s, k, 1)) And &HFFFF&), 4)
    Next
    Units = h
End Function
