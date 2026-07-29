# Resultados publicados y widget

PixelPy separa el archivo de trabajo del resultado publicado. Al terminar correctamente una automatización, copia el archivo destacado a su almacenamiento privado mediante reemplazo atómico. Una ejecución fallida conserva la última copia correcta.

## Flujo

1. La automatización ejecuta el script guardado.
2. PixelPy verifica que el resultado configurado fue creado o actualizado durante esa ejecución.
3. Se publica una copia segura en `filesDir/published/<automation-id>/`.
4. El widget se actualiza con estado, fecha, nombre y tamaño.
5. **VER RESULTADO** abre el visor integrado de PixelPy.

El widget nunca abre directamente el archivo que está escribiendo el script.

## Ejecuciones normales

La consola muestra cada archivo creado o actualizado por la ejecución con tres acciones:

- **VER RESULTADO:** abre la misma vista previa integrada.
- **GUARDAR:** exporta una copia mediante el selector de Android.
- **COMPARTIR:** entrega el archivo a otra aplicación.

En este caso PixelPy abre el archivo actual del proyecto. La copia estable en `published/` se reserva para automatizaciones y widgets.

## Vista previa

| Formato | Comportamiento |
| --- | --- |
| TXT, LOG, Markdown, XML | Vista de texto monoespaciada |
| JSON | Vista indentada cuando el JSON es válido |
| CSV | Primeras 200 líneas |
| PNG, JPG, JPEG, WebP | Vista de imagen |
| PDF, XLS, XLSX, ZIP y otros | Información del archivo y apertura con otra aplicación |

La lectura de texto se limita a 1 MB. El archivo completo permanece disponible mediante **OTRA APP** o **COMPARTIR**.

## Estados del widget

- **PENDIENTE:** todavía no hay una ejecución finalizada.
- **EJECUTANDO:** el Worker está usando o esperando el runtime de Python.
- **CORRECTO:** existe un resultado publicado que puede verse.
- **ERROR:** la tarjeta abre el diagnóstico y conserva el último resultado correcto.
- **PAUSADA:** no se ejecutará según el horario hasta reactivarla.

El botón **EJECUTAR** solo encola el trabajo; Python no se ejecuta dentro del widget. Pulsaciones rápidas no crean ejecuciones duplicadas.

## Si VER RESULTADO no abre

1. Abre PixelPy y comprueba el estado de la automatización.
2. Si indica error, toca su tarjeta para consultar el diagnóstico.
3. Ejecuta nuevamente la automatización y confirma que el script actualiza el archivo destacado.
4. Si el visor integrado abre pero **OTRA APP** no funciona, instala una aplicación compatible con ese formato.

Cuando el archivo publicado falta o su ruta deja de ser válida, PixelPy muestra un estado de resultado no disponible en lugar de provocar un cierre inesperado.
