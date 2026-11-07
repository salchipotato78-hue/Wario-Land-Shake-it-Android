# Workflow Android: la traducción ya se reutiliza y se guarda

## Causa
La clave de caché de la traducción usaba hashFiles('fw/translator/**') y se evaluaba dos veces:
al restaurar y al guardar. Entre las dos, `dotnet build` crea bin/ y obj/ dentro de fw/translator,
y esos archivos cambiaban el hash. Resultado: cada guardado quedaba bajo una clave que la siguiente
ejecución nunca pedía, así que siempre se volvía a traducir.

## Cambio (solo .github/workflows/android.yml)
- Nuevo paso "Translation cache key" al inicio: calcula la clave una sola vez con los árboles de git
  de fw/translator y projects/wario (solo archivos versionados) y el sha256 de main.dol.
  Formato: translated-v2-<dol>-<árbol traductor>-<árbol proyecto>.
- Restaurar y guardar usan esa misma clave (steps.tkey.outputs.key).
- Se anota en el log la clave usada.

## Cómo comprobarlo en la primera ejecución
1. Ejecución 1 (con este cambio): "Translate the game" corre una vez y "Save translated code" guarda
   la clave v2. Es normal que tarde lo mismo.
2. Ejecución 2 (sin cambiar el juego ni el traductor): "Restore translated code" debe dar cache-hit,
   "Translate the game" debe quedar saltado, y la clave del aviso debe ser la misma de la ejecución 1.
3. Las claves translated-* viejas ya no coinciden con nada. Se pueden borrar con
   gh cache delete <id> (ver gh cache list) para no ocupar espacio.

## Pendiente (no hecho, medir antes)
- La caché de ccache (3 GB) se guarda completa en cada ejecución con un nombre nuevo. Ese guardado
  puede costar varios minutos. Si tras el cambio de arriba sigue siendo lento, el siguiente paso es
  guardarla solo cuando el acierto de ccache baje.
- Sin ejecutar GitHub Actions aquí no puedo medir los tiempos reales; el YAML se validó localmente.
