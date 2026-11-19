# Wario Android: optimización guiada por perfil (PGO)

Portado de DriftDroid (rama Android de MKW). Solo se usa la rama de Android (clang); la del Switch
(GCC, devkitA64) no aplica aquí.

## Archivos cambiados
- runtime/src/android_pgo.cpp (nuevo): escribe el perfil cada 20 s en
  <datos de la app>/pgo/game.profraw. Solo compila con -DMKW_PGO=generate.
- runtime/CMakeLists.txt: opción MKW_PGO (generate / use) y MKW_PGO_PROFILE. Solo afecta a Android.
- runtime/src/main.cpp: arranca el recorder al iniciar, justo antes de configurar Aurora. Solo en
  builds de generate.

## Cómo generar el build optimizado
1. Build de grabación: cmake -DMKW_PGO=generate ... y empaquetar como APK DEBUG (el perfil se lee
   con run-as en algunos dispositivos; ver nota de rutas abajo).
2. Jugar un rato real (un tramo de 40-50 s donde se ven los tirones, y una o dos escenas más).
3. Traer el perfil y fusionarlo (llvm-profdata del NDK):
     adb exec-out run-as com.wario.shakeit cat files/pgo/game.profraw > game.profraw
   Si run-as no alcanza la carpeta (la carpeta de datos de Wario es la externa), usar:
     adb pull /sdcard/Android/data/com.wario.shakeit/files/pgo/game.profraw
   Verificar la ruta real en el log "PGO recording build: profile written every 20 s to ...".
     llvm-profdata merge -o android.profdata game.profraw
   Usar solo el último snapshot (el compilador agrega; si se mezclan, el inicio cuenta varias veces).
4. Build final: cmake -DMKW_PGO=use -DMKW_PGO_PROFILE=/ruta/android.profdata ...

## Medir sin trampas
- Probar con el cable desconectado. Con el cable conectado el teléfono se calienta
  por la carga y el resultado sale peor.
- Medir con build release, no debug.
- Comandos (desde la PC, con el teléfono conectado solo para medir o por Wi-Fi):
    adb shell top -H -b -n 1 -p $(adb shell pidof com.wario.shakeit)
    adb shell simpleperf record --app com.wario.shakeit --duration 20 -f 2000 -o /data/local/tmp/wario.data
  Para el ritmo de presentación, el compositor (dumpsys SurfaceFlinger --latency) da el tiempo de cada
  cuadro mostrado.

## No portado (y por qué)
- Hilo GX con cola (GxWorker, fifo.cpp): en Wario el tiempo de GX fue 4-6 ms y el de juego
  40-70 ms, así que mover GX al otro hilo no ataca lo que más pesa. Revisar después de tener el perfil.
- Surface.setFrameRate (fijar la tasa a la del juego): choca con la interpolación a 120 FPS, que
  necesita un panel de 120 Hz para mostrarse. Decidir con el usuario antes.

## Verificación
- android_pgo.cpp compila con g++ -Wall -Wextra -Werror (con cabeceras de prueba) y arranca el recorder una
  sola vez.
- NO verificado: CMake y el build de Android (no hay cmake ni NDK en este entorno). El bloque de CMake
  sigue la misma forma que el de DriftDroid; hay que confirmar que compila en el primer build.
