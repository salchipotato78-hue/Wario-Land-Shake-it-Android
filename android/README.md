# Wario Land: Shake it! — Android

Port Android del runtime (WarioRecomp) con:

- **Ultrawide sin estirar:** el juego se dibuja a la proporción de la pantalla (aspecto "fill").
- **Shake por movimiento:** sacude o gira rápido el teléfono para que Wario se sacuda (acelerómetro y
  giroscopio). El botón Shake sigue disponible como alternativa.
- **Apuntar arrastrando:** aparece una punta amarilla en la pantalla. Arrástrala para apuntar en los cañones
  y en la flecha de mover objetos; se queda donde la sueltes.
- **Controles táctiles editables:** joystick, 1, 2, A, +, − y Shake. Cada control se puede mover,
  cambiar de tamaño (50–200 %) y de opacidad (20–100 %). Los cambios se guardan en el teléfono.
  Para editar, toca el botón ⚙ de arriba a la izquierda; "Restaurar" vuelve a la disposición inicial.
- **Build solo con GitHub Actions:** `.github/workflows/android.yml` (ejecutar a mano con *Run workflow*).

## Requisitos

**Teléfono:** Android 10 (API 29) o superior, arm64, con Vulkan 1.1. Android 8 y 9 ya no se soportan: el motor
necesita el TLS nativo de API 29 para no perder velocidad.

1. **En el teléfono:** un `.zip` con la carpeta DATA de tu disco (`sys/main.dol`, `sys/boot.bin`, `sys/fst.bin`
   y `files/`), de la versión US RWLE01 revisión 0. Al abrir la app, pulsa **Elegir zip de datos** y selecciónalo
   con el explorador de Android. El zip se descomprime una vez en la carpeta de la app y se comprueba (cabecera
   RWLE01 rev. 0 y SHA-256 de `main.dol`). Para cambiarlo más tarde, abre el menú ⚙ en el juego y pulsa
   **Cambiar datos del juego…**.
2. **En GitHub (para compilar el APK):** da el `main.dol` de tu disco de una de estas dos formas:
   - **Secreto `WARIO_DATA_URL`** (recomendado): en *Settings → Secrets and variables → Actions*, crea el secreto con
     un enlace de descarga directa al archivo `sys/main.dol` (por ejemplo, un enlace de Dropbox). También vale un zip
     que contenga `sys/main.dol`. Pesa pocos MB; el zip completo sólo hace falta en el teléfono.
   - **Un release** llamado `game-data` en el repo, con `main.dol` adjunto.
   El workflow comprueba el SHA-256 en ambos casos. No compartas el enlace: da acceso a tu copia del juego.
3. Ejecuta el workflow "Android APK". Al terminar, descarga el artefacto `wario-land-shake-it-apk`.

## Cómo llegan los datos al runtime

`SetupActivity` (lanzador) descomprime el zip en `Android/data/com.wario.shakeit/files/DATA` y escribe
`[paths] dvd_root` en `Config.toml`, que es donde el runtime busca el juego. `DataImporter` hace ese trabajo sin
depender de Android, así que se prueba en el PC: `javac -d out android/app/src/main/java/com/wario/shakeit/DataImporter.java android/tests/com/wario/shakeit/DataImporterTest.java && java -cp out com.wario.shakeit.DataImporterTest`. `GameActivity` corre en el proceso
`:game`; el runtime lee la carpeta una vez por proceso, por eso "Cambiar datos" reinicia ese proceso.

## Estructura

| Ruta | Contenido |
|---|---|
| `app/src/main/java/com/wario/shakeit/SetupActivity.java` | Lanzador: elegir el zip de datos e importarlo |
| `app/src/main/java/com/wario/shakeit/DataImporter.java` | Descomprimir, validar y escribir `Config.toml` (sin Android) |
| `app/src/main/java/com/wario/shakeit/GameActivity.java` | Activity (extiende SDLActivity), variables del runtime y overlay |
| `app/src/main/java/com/wario/shakeit/TouchOverlay.java` | Controles táctiles, edición y envío al runtime |
| `app/src/main/assets` ← `runtime/assets` | DSP, caché de pipeline, NAND de arranque y CA (se desempaquetan al primer inicio) |
| `runtime/src/android/android_bridge.cpp` | JNI: `nativeSetTouchState`, `nativeApplyDisplaySettings` |

## Pendiente de verificar en el teléfono

- Que el APK arranque en un teléfono real con un zip correcto (la importación está probada en el PC con un
  zip sintético; el juego real solo se puede comprobar en el dispositivo).
- Que `libSDL3.so` se empaquete junto a `libmain.so` con el build de Aurora para Android.
- Rendimiento y backend gráfico (Dawn/Vulkan) en GPUs Mali.
- Los menús de red local: `GameActivity.java` no declara `nativeTap`, `nativeTakeSettings` ni
  `restartForSession`, que sí existen en `runtime/src/android/android_bridge.cpp`. Hasta conectarlos, los menús
  de LAN no responden al toque y el reinicio de sesión no funciona desde Java.
