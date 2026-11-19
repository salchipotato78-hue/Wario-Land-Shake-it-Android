# Rendimiento: lo que se portó desde el repo nativo (Wario-Land-Shake-It-Native)

Revisión comparada de los dos repos. El repo nativo tiene varias cosas que la rama Wii no tenía
para los tirones de textura y de resolución. Se portaron las que encajan con la base Wii. Lo que la
rama Wii ya hacía mejor se dejó como estaba (ver abajo).

## Portado

### 1. Pool de texturas reciclables (aurora/lib/gfx/texture.cpp, texture.hpp, common.cpp)
- Crear una textura en Mali pasa por un binder IPC síncrono (AFBC). El repo nativo midió ~12 % del
  hilo de juego en creación de texturas en una escena pesada.
- Las texturas liberadas se guardan por forma (ancho, alto, mips, formato) y se reutilizan.
  Límites: 48 MiB, 16 por forma, 600 frames de antigüedad.
- Diferencia con nativo: aquí `TextureHandle` sigue siendo `shared_ptr<TextureRef>`, con un deleter
  que devuelve la textura al pool. No se cambió el tipo, así que el resto del código no se toca.
- Cuando una subida corta no llega a todos los mips, los mips restantes se rellenan con ceros. Una
  textura reciclada puede traer la última imagen y eso no debe verse.
- Al cerrar (shutdown) el pool se cierra: lo que se libere después se destruye en vez de guardarse.
- **Corrección respecto al nativo:** en el nativo el desalojo por antigüedad sólo corría cuando el
  pool ya estaba sobre presupuesto, así que las texturas viejas nunca se liberaban en una escena
  tranquila. Aquí el barrido por antigüedad corre una vez por frame.
- Contadores `texturesBuilt` / `texturesRecycled` en `AuroraStats` (gfx.h). Se ven en el log
  "slow frame" de vi.cpp.

### 2. Resolución dinámica (runtime/src/dynamic_resolution.cpp, include/dynamic_resolution.h)
- Baja la resolución de render sólo mientras los frames llegan tarde por la GPU, y la sube otra vez
  cuando sobra tiempo. No toca la configuración del jugador.
- Se multiplica con el factor térmico que ya tenía Wii: `líneas = elegidas × térmico × dinámico`,
  redondeado a un número par de líneas.
- Activo por defecto en Android (`kDefaultEnabled`). No hay clave en Config.toml todavía;
  `DynamicResolution::SetEnabled(false)` lo apaga.
- Se engancha en vi.cpp, dentro del bloque de presentación, usando el mismo tiempo de vblank que
  ya usa el pacer.

### 3. Espera con `yield` en ARM (runtime/src/hle/vi.cpp)
- El bucle final de espera al vblank giraba sin ninguna pista al procesador. Ahora usa `yield` en
  aarch64 y `pause` en x86, como ya hacía `YieldProcessor` en Windows. Menos consumo mientras se espera.

### 4. Arreglo de enlazado de Dawn (aurora/cmake/AuroraDawnProvider.cmake)
- El paquete prebuilt de Dawn guarda la ruta absoluta de la sysroot de la máquina que lo compiló
  (`liblog.so`). En otra máquina ninja falla con "missing and no known rule". Se reescribe a nombre
  simple, que se resuelve con el NDK en uso.

## No portado (y por qué)

- **Caché de pipelines (pipeline_cache.cpp).** Nativo usa 2 workers con 4 CPUs reservadas. Wii usa
  1 worker con prioridad baja (nice 10) y tiene el vaciado de escrituras al salir. Wii es más
  conservador con la CPU del juego y no se cambió.
- **Fifo de GX (fifo.cpp).** Nativo reutiliza el buffer de comandos, pero no protege el caso en que
  GX vuelve a entrar durante el decode (los handlers de alarma escriben comandos). Wii ya lo cubre.
  Reutilizar el buffer sería una mejora pequeña y se dejó para después.
- **ADPF, reloj de vblank por Choreographer, térmico, PGO, replay governor, frame worker opt-in,
  nativeSurfaceReady.** Wii ya los tiene y el nativo no.
- **CheckTlsSize.cmake.** Sólo se usa desde los scripts de PowerShell del repo nativo, que aquí
  no existen.

## minSdk: 26 → 29 (aplicado)

Por debajo de API 29 el NDK emula el TLS, y el runtime lee `thread_local` en casi cada instrucción
invitada. Subir a 29 quita esa emulación. Se pierden Android 8 y 9. Cambiado en
`android/app/build.gradle.kts` y documentado en `android/README.md`.

## CI: por qué tardaba una hora y no reutilizaba la caché

Causas encontradas en `.github/workflows/android.yml`, y lo que se hizo con cada una:

- **ccache se guardaba entero en cada run** (3 GB), aunque el run acertara casi todo. Ahora se guarda
  sólo si no había caché que restaurar o si más del 3 % de las compilaciones falló. Si el build falla,
  se guarda igual, como antes.
- **ccache podía invalidarse por completo**: su comprobación del compilador usa el mtime, y el NDK
  restaurado de caché no garantiza el mismo. Ahora `CCACHE_COMPILERCHECK=string:<versión NDK>`.
- **Las dependencias se descargaban en cada run** (SDL, Dawn, abseil, fmt, imgui…). Ahora
  `FETCHCONTENT_BASE_DIR` apunta a `.fetch/`, y se cachea sólo la parte de descarga (`*-src` y
  `*-subbuild`), no los `*-build`, que ccache ya reutiliza.
- **sdkmanager consultaba el índice remoto en cada run**, aunque no se descargara nada. Ahora la
  plataforma, build-tools y CMake se cachean, y si están presentes se omite sdkmanager.
- **`apt-get update` en cada run** sólo para instalar ccache. Ahora se instala sólo si falta.
- **NuGet se descargaba en cada `dotnet build` del traductor.** Ahora se cachea.
- **El resumen del job no decía nada.** Ahora muestra tiempo de gradle, aciertos de ccache y si se
  guardó la caché.

No se pudo medir el tiempo real: no hay runners de GitHub en este entorno. Los puntos de mayor
impacto esperado son el guardado de ccache y la descarga de dependencias. Si el primer run con el
cambio sigue tardando mucho, el resumen dirá dónde.

Lo que **no** se hizo, y por qué:
- Separar la traducción y la compilación de dependencias en dos jobs paralelos. Ahorraría minutos,
  pero cambia el flujo de artefactos y no se puede probar aquí.
- Un runner más grande. Es lo que más ayudaría (el runner público tiene 4 núcleos), pero depende de
  la cuenta de GitHub.

### Lo que mostraron los logs de los primeros runs reales

- **Cachés que no se guardaban en runs fallidos.** `actions/cache@v4` guarda en su paso *post* y sólo si el job
  termina bien. Un run que falla (por ejemplo, en el build) nunca guardaba la traducción ni las descargas. Ahora
  la traducción y las descargas se guardan con `actions/cache/save` explícito.
- **`setup-gradle` rechazaba el build.** Las descargas de dependencias estaban dentro del workspace, y
  `setup-gradle` valida todos los `gradle-wrapper.jar` del workspace. El de SDL no es uno conocido. Ahora
  `WARIO_FETCH_DIR` está en `runner.temp`, fuera del workspace.
- **El build de APK llegó a compilar** (796 objetos de ccache, 0 aciertos en el primer run). Es esperable: la
  traducción se regeneró, así que los fuentes generados cambiaron. Con la traducción restaurada, los aciertos
  deberían subir en el siguiente run.

## Cómo verificarlo (no se pudo compilar aquí)

En este entorno no hay NDK, CMake, ni Dawn/fmt/Tracy. Lo que sí se probó:
- `dynamic_resolution.cpp` compila sin avisos con `-Wall -Wextra -std=c++20`.
- La lógica del pool (reciclaje, límite por forma, presupuesto, antigüedad, pool cerrado, mips
  sin escribir) se probó con un arnés que usa tipos wgpu simulados: 8 comprobaciones, todas pasan.
- `texture.cpp`, `vi.cpp`, `display_settings.cpp` no se compilaron completos (faltan las
  dependencias). Sólo se revisó su diff a mano.

Pasos para el primer build:
1. Ejecutar el workflow dos veces seguidas sin cambios. En la segunda, el resumen debe mostrar la caché restaurada y, si hubo pocos fallos, "¿Se guarda ccache?: false".
2. Compilar el APK normal. Si falla algo en `texture.cpp`, lo más probable es un nombre que no
   coincide con las cabeceras de Dawn.
3. En el log, buscar `dynamic resolution` y `textures N built (M recycled)`. Si `recycled` sube con
   el tiempo, el pool funciona.
4. Comparar con el build anterior en la misma escena pesada: frames > 20 ms y líneas del log
   `display: ... render N lines (..., dynamic D)`.
5. Si hay pantalla negra o artefactos en texturas, apagar la resolución dinámica con
   `SetEnabled(false)` y mirar si el problema sigue. Si sigue, el pool es la causa, y se puede
   desactivar cambiando `initialize_texture_pool()` por un no-op.
