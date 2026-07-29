# PixelPy

Editor y entorno de ejecución de Python para Android, diseñado para trabajar directamente desde el teléfono y guardar todo localmente.

## Funciones

- Editor multifichero con resaltado, búsqueda, autocompletado y versiones.
- Proyectos con scripts y recursos CSV, JSON, Excel, imágenes, TXT y ZIP.
- Ejecución local con `input()`, detención, análisis previo y depuración de variables.
- Consola separada en salida, variables y errores.
- REPL persistente por proyecto.
- Importación y exportación de proyectos ZIP.
- Incluye `requests`, `beautifulsoup4`, `openpyxl` y `defusedxml`.
- Autosave seguro con recuperación de proyecto, archivo, pestaña y cursor.
- Automatizaciones únicas, diarias o semanales mediante WorkManager, con restricciones de red, carga y batería.
- Publicación segura de resultados y widget para consultar el estado, ejecutar el script y ver el último archivo generado.
- Visor integrado para texto, Markdown, XML, JSON, CSV e imágenes, con compartir y apertura externa para otros formatos.
- Los archivos generados por una ejecución normal aparecen en una galería horizontal y pueden abrirse en el mismo visor directamente desde la consola.
- Diagnóstico de automatizaciones con historial local, causa visible en el widget, reintento y copia del reporte.
- Interfaz neobrutalista optimizada para teclado móvil.

## Capturas

<p align="center">
  <img src="docs/screenshots/projects.png" alt="Gestor de proyectos de PixelPy" width="31%" />
  <img src="docs/screenshots/editor.png" alt="Editor multifichero de PixelPy" width="31%" />
  <img src="docs/screenshots/console.png" alt="Consola de ejecución de PixelPy" width="31%" />
</p>

<p align="center">
  <img src="docs/screenshots/automation.png" alt="Configuración de automatizaciones de PixelPy" width="46%" />
  <img src="docs/screenshots/widget.png" alt="Widget de resultados de PixelPy" width="46%" />
</p>

## Resultados publicados

El botón **VER RESULTADO** del widget, **ABRIR RESULTADO** en automatizaciones y **VER RESULTADO** en la consola llevan al mismo visor:

- TXT, LOG, Markdown y XML se muestran como texto.
- JSON se presenta como un árbol interactivo con objetos y listas plegables.
- CSV se muestra como tabla con encabezados, filas alternadas y desplazamiento horizontal.
- PNG, JPG, JPEG y WebP se muestran dentro de PixelPy.
- PDF, Excel, ZIP y formatos desconocidos muestran sus datos y ofrecen **OTRA APP**.
- Todos los formatos pueden compartirse desde el visor.

La vista previa textual está limitada a 1 MB para evitar bloqueos. El visor siempre usa la copia publicada en el almacenamiento privado de PixelPy, no el archivo que el script podría estar modificando.

En la consola normal se abre el archivo generado dentro del proyecto. En automatizaciones y widgets se abre una copia publicada estable.

Consulta el flujo completo y la resolución de problemas en [Resultados publicados y widget](docs/resultados-publicados.md).

## Requisitos

- Android 8.0 o superior.
- Dispositivo ARM64.
- Android Studio con JDK 17.
- Python 3.13 instalado en el equipo de compilación para Chaquopy.

## Compilar

```powershell
.\gradlew.bat :pyeditor:assembleDebug
```

El APK se genera en `pyeditor/build/outputs/apk/development/debug/`.

## Versión

La versión publicada actual es `1.2.1`. Añade un visor interno de resultados, una apertura más confiable desde el widget y una presentación más clara del archivo publicado.

Las ejecuciones programadas son aproximadas: Android puede retrasarlas por batería, Doze o restricciones del sistema.

## Privacidad

Los proyectos, automatizaciones y resultados publicados se guardan localmente. PixelPy solo usa Internet cuando un script del usuario realiza una solicitud de red.
