"""CSVs written by Python's `csv` module, for the CSV editor's oracle, next to Excel's
(make_excel_csv.vbs): line breaks LF, every field quoted, empty lines and a `sep=` line, a last line
without its line break. Usage: python -I make_python_csv.py tools/corpus/csv-editor"""
import csv
import io
import os
import sys

folder = sys.argv[1]
ROWS = [
    ["id", "name", "note", "amount"],
    ["1", "Zoé", "two\nlines", "-5"],
    ["2", "Bob, Jr.", 'say "hi"', "+33612345678"],
    ["3", "", "=A1 from the file", "0,25"],
    ["4", "Ève", "", ""],
]


def text(rows, **options):
    out = io.StringIO(newline="")
    csv.writer(out, **options).writerows(rows)
    return out.getvalue()


def save(name, data):
    with open(os.path.join(folder, name), "wb") as handle:
        handle.write(data)


save("python-lf.csv", text(ROWS, lineterminator="\n").encode("utf-8"))
save("python-quote-all-semicolon.csv", text(ROWS, delimiter=";", quoting=csv.QUOTE_ALL, lineterminator="\r\n").encode("utf-8"))
blank = text(ROWS[:3], lineterminator="\r\n") + "\r\n\r\n" + text(ROWS[3:], lineterminator="\r\n") + ",,,\r\n\r\n"
save("python-sep-blank-lines.csv", ("sep=,\r\n" + blank).encode("cp1252"))
excel = open(os.path.join(folder, "excel-utf8-local.csv"), "rb").read()
assert excel.endswith(b"\r\n")
save("excel-utf8-no-final-break.csv", excel[:-2])
