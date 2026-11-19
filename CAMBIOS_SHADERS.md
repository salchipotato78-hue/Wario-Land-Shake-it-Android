# Tirones al entrar en zonas nuevas y al hacer el shake

## Síntoma

El juego va bien en general, pero al entrar en zonas nuevas o al hacer el golpe hacia abajo (shake) el
framerate cae unos segundos. También pasa en zonas muy rápidas.

## Causa confirmada: la semilla de pipelines está obsoleta

`runtime/assets/pipeline/initial_pipeline_cache.db` trae 1153 pipelines GX de la **versión 19**. El motor
está en la **versión 20** (`GXPipelineConfigVersion` en `aurora/lib/gx/pipeline.hpp`). Al arrancar sólo se
cargan las filas de la versión actual, así que **ningún pipeline GX de la semilla se usa**. Cada efecto o
pantalla nueva se compila en el momento en que aparece.

Lo que hacía el código, y que ocultaba el problema:
- `seed_pipeline_cache()` fusionaba todas las filas sin mirar la versión y luego informaba
  "1156 rows merged", como si estuvieran cargadas.
- `tools/check_pipeline_seed.py` detecta la versión obsoleta, pero no se ejecutaba en CI.

**Cambios hechos:**
- `pipeline_cache.cpp`: las filas de versión distinta ya no se fusionan. Si hay filas obsoletas, el log
  lo dice con un `WARN`: `Bundled pipeline cache is stale: N rows ignored...`.
- `tools/promote_pipeline_seed.py` (nuevo): genera una semilla a partir de una caché escrita por el motor,
  conservando sólo las filas de la versión actual y del tamaño de config que escribe el build. Probado con
  una caché de prueba: conserva las filas válidas y descarta las obsoletas y las de layout distinto. No
  modifica el archivo de origen.
- `.github/workflows/android.yml`: un paso avisa (sin fallar el build) si la semilla está obsoleta.

**Pendiente, y necesita tu acción:** regenerar la semilla. Con la versión 19 no hay forma de usarla,
y no convertí las filas a mano porque la versión sube precisamente cuando cambia cómo se interpreta
la config. Un pipeline convertido mal daría gráficos mal, sin error visible.

### Cómo regenerarla

1. Ejecuta el build de PC (o el de Android en modo debug) y juega las zonas que tiran: la zona nueva,
   el shake, las zonas rápidas. Cuantos más pipelines se vean, mejor la semilla.
2. Localiza la caché que escribió el motor (`initial_pipeline_cache.db` en la carpeta de recursos del PC
   build; en Android, `pipeline_cache.db` dentro de la carpeta de la app).
3. `python3 tools/promote_pipeline_seed.py <esa_caché.db>`. Muestra cuántas filas conserva.
4. `python3 tools/check_pipeline_seed.py` debe salir con código 0.
5. Sube `RUNTIME_STAMP` en `WarioApplication.java` (ahora `unpacked-4`, pasa a `unpacked-5`). Sin eso, los
   teléfonos que ya tienen la copia vieja no la reemplazan.

Nota: las recetas GX describen el estado de render, no la GPU. Una semilla hecha en PC debería servir en
el teléfono, pero conviene comprobarlo: si las filas no se usan, el log de arranque lo mostrará en
`Seeded pipeline cache ... rows merged`.

## Interruptor: migrar la semilla v19 a v20 (apagado por defecto)

Sin regenerar la semilla, las 1153 recetas GX de la versión 19 se pueden reutilizar. Están activadas con:

- `Config.toml`: `[video] migrate_stale_pipelines = true`, o la casilla **Migrate old shader cache (next launch)**
  en el menú de ajustes. Surte efecto en el siguiente arranque.

Qué hace, al cargar la semilla:
1. Sólo convierte filas de versión 19 de tipo GX.
2. Sólo si su tamaño coincide con el `sizeof` de la config actual. Si el layout cambió, no convierte nada y
   lo dice en el log (`Pipeline migration skipped`).
3. Cambia el campo `version` a 20, valida la receta con `valid_pipeline_config` y recalcula el hash igual que
   `find_pipeline` (xxh3 del struct con semilla = tipo).

Qué se comprobó aquí, con la semilla real:
- Los 1153 hashes guardados coinciden con `XXH3(bytes, semilla = 1)`, así que el cálculo del hash es el correcto.
- El campo `version` está en el byte 0 y vale 19 en todas las filas. Todas tienen `msaaSamples = 1` y tamaño 2768.
- La conversión produce 1153 hashes nuevos y distintos.

Qué NO se comprobó, y es lo que decide si se activa:
- Que la versión 20 no haya cambiado el significado de ningún campo. Esto sólo se ve jugando: si un efecto se
  ve mal con el interruptor encendido y bien apagado, la migración no sirve y se deja apagada.
- El `sizeof` actual (el programa que lo mide depende de las cabeceras de Dawn y no se pudo compilar aquí).

Prueba recomendada: arranca dos veces. Primero con el interruptor apagado y anota el tiempo de
`Pipeline prewarm finished`. Luego encendido, y compara los efectos de la zona que tira y del shake.

## Causa probable, sin confirmar: compilación en segundo plano y bloqueo del driver

Con la semilla válida, las zonas ya vistas no compilan nada. Pero un efecto nuevo sí compila, y hay dos
cosas que pueden frenar el hilo de juego:

- **Un solo worker de compilación en Android** (`pipeline_worker_count()` devuelve 1). Cuando aparecen varios
  pipelines a la vez, forman una cola que tarda segundos.
- **Pases persistentes que esperan.** `wait_pipeline_for_persistent_pass` bloquea el hilo de juego hasta que
  el pipeline esté listo. Si ese pipeline está al final de la cola de compilación, el juego se queda
  parado el tiempo que tarde.

Además, en drivers Mali antiguos la creación de pipelines toma un lock por proceso, así que compilar en
segundo plano también retrasa los envíos del hilo de juego (ARM Community: el driver "takes too much time"
con pipelines en lote). Eso encaja con un tirón de varios segundos, no con una caída instantánea.

No está confirmado. Los logs lo distinguen: `Pipeline prewarm finished` indica cuánto tarda la precarga, y
las líneas `slow frame` muestran `textures N built` y el tiempo de `gx`.

## Zonas rápidas

Sin logs no puedo atribuir el problema. Las hipótesis son: muchas texturas nuevas por frame (el pool de
`CAMBIOS_RENDIMIENTO.md` ayuda sólo a formas repetidas) o pipelines nuevos por cambios de estado. Los
contadores `textures N built (M recycled)` del log de frames lentos dirán cuál de las dos.

## Qué no se hizo

- Limitar la espera de pases persistentes. Cambia la semántica de un pase que hornea una textura, y un
  pipeline dibujado a medias corrompe esa textura de forma permanente. Requiere pruebas en el teléfono.
- Subir el número de workers en Android. Lo bajamos a propósito, porque la compilación en paralelo robaba
  CPU al hilo de juego.

## Qué mirar en el log tras regenerar la semilla

1. `Seeded pipeline cache ... rows merged` sin el aviso de semilla obsoleta.
2. `Pipeline prewarm finished: N pipelines in X s`. Con una semilla buena, X debe ser corto.
3. En zonas nuevas, pocos `slow frame`. Si siguen, copia las líneas con `textures` y `gx` para analizarlas.
