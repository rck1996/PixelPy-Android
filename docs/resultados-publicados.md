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

La consola reúne los archivos creados o actualizados en una galería horizontal, manteniendo visibles la salida y las acciones principales. Cada resultado ofrece:

- **VER RESULTADO:** abre la misma vista previa integrada.
- **GUARDAR:** exporta una copia mediante el selector de Android.
- **COMPARTIR:** entrega el archivo a otra aplicación.

En este caso PixelPy abre el archivo actual del proyecto. La copia estable en `published/` se reserva para automatizaciones y widgets.

Cada ejecución manual también crea una sesión local independiente. El historial conserva el código ejecutado, la salida y una copia de sus archivos generados. Las sesiones fijadas no se eliminan durante la limpieza automática; las demás conservan las 20 más recientes. Desde **HISTORIAL** se puede:

- abrir un resultado histórico aunque el archivo de trabajo haya cambiado;
- fijar o borrar una sesión;
- comparar el resumen de salida con la ejecución anterior del mismo script;
- exportar código, salida y resultados en un ZIP.

## Vistas integradas

- Texto, CSV y JSON incluyen búsqueda.
- Markdown se renderiza con títulos, listas y citas.
- Las imágenes permiten zoom táctil.
- ZIP muestra hasta 500 entradas sin extraer contenido.
- Excel muestra una vista básica de las primeras 200 filas de la primera hoja.

Los libros complejos, fórmulas, estilos avanzados y formatos no compatibles pueden abrirse con **OTRA APP**.

## Parámetros y avisos

Una automatización puede guardar hasta 20 parámetros `CLAVE=valor`. Durante su ejecución están disponibles mediante `os.getenv("CLAVE")` y se eliminan o restauran al terminar. Android solicita permiso para notificaciones al entrar por primera vez a Automatizaciones; al finalizar, el aviso ofrece **VER RESULTADO** cuando existe un archivo publicado.

## Vista previa

| Formato | Comportamiento |
| --- | --- |
| TXT, LOG, Markdown, XML | Vista de texto monoespaciada |
| JSON | Árbol interactivo con objetos y listas plegables y tipos diferenciados por color |
| CSV | Tabla con encabezados, filas alternadas y hasta 200 registros |
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
