package com.pixelpy.editor

internal data class ScriptTemplate(val id: String, val name: String, val description: String, val fileName: String, val source: String)

internal val PIXELPY_TEMPLATES = listOf(
    ScriptTemplate("api", "Consultar una API", "Descarga JSON con timeout y errores claros.", "consulta_api.py", """import requests

url = "https://jsonplaceholder.typicode.com/todos/1"
respuesta = requests.get(url, timeout=20)
respuesta.raise_for_status()
print(respuesta.json())
"""),
    ScriptTemplate("csv", "Analizar CSV", "Resume columnas y cantidad de filas.", "analizar_csv.py", """import csv

with open("datos.csv", encoding="utf-8") as archivo:
    filas = list(csv.DictReader(archivo))
print("Filas:", len(filas))
print("Columnas:", list(filas[0]) if filas else [])
"""),
    ScriptTemplate("excel", "Crear Excel", "Genera un libro con tabla y fórmula.", "crear_excel.py", """from openpyxl import Workbook

libro = Workbook()
hoja = libro.active
hoja.title = "Resumen"
hoja.append(["Producto", "Monto"])
hoja.append(["Ejemplo", 12500])
hoja["B3"] = "=SUM(B2:B2)"
hoja.freeze_panes = "A2"
libro.save("reporte.xlsx")
print("reporte.xlsx generado")
"""),
    ScriptTemplate("chart", "Gráfico Plotly", "Crea un HTML interactivo sin servidor.", "grafico_plotly.py", """import json

datos = [{"x": ["A", "B", "C"], "y": [4, 7, 3], "type": "bar"}]
html = f'''<html><body><div id="grafico"></div>
<script src="https://cdn.plot.ly/plotly-2.35.2.min.js"></script>
<script>Plotly.newPlot("grafico", {json.dumps(datos)});</script></body></html>'''
open("grafico.html", "w", encoding="utf-8").write(html)
print("grafico.html generado")
"""),
    ScriptTemplate("scraping", "Extraer una web", "Obtiene título y enlaces con BeautifulSoup.", "scraping.py", """import requests
from bs4 import BeautifulSoup

html = requests.get("https://example.com", timeout=20).text
soup = BeautifulSoup(html, "html.parser")
print("Título:", soup.title.string if soup.title else "Sin título")
for enlace in soup.select("a[href]")[:10]:
    print(enlace.get_text(" ", strip=True), enlace["href"])
"""),
    ScriptTemplate("automation", "Resultado automatizado", "Base segura para publicar un resultado.", "reporte_automatico.py", """import os
from datetime import datetime

nombre = os.getenv("NOMBRE", "PixelPy")
contenido = f"Reporte de {nombre}\nActualizado: {datetime.now():%d-%m-%Y %H:%M}\n"
open("resultado.txt", "w", encoding="utf-8").write(contenido)
print("resultado.txt actualizado")
"""),
)
