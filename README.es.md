<div align="center">

# NFSMW Recompiled

**Need for Speed: Most Wanted (2005) nativo para Android ARM64**

APK nativo · Vulkan 1.1+ · Mali Bifrost y Valhall · herramientas AOT

[English](README.md) · [Última versión](https://github.com/CodeAbhi826/nfsmwrecomp-mali/releases)

</div>

**NFSMW Recompiled** es una recompilación nativa de *Need for Speed: Most Wanted (2005)* para Android.
No emula una Xbox 360: el código del juego se traduce a C++ y se ejecuta directamente sobre Vulkan, con
las herramientas del [ReXGlue SDK](https://github.com/rexglue/rexglue-sdk) y el trabajo previo de
[nfsmw-nx](https://github.com/StevensND/nfsmw-nx) y
[codepdbh/nfsmw-android](https://github.com/codepdbh/nfsmw-android).

Este repositorio **no contiene archivos del juego**: ni el ejecutable, ni texturas, ni audio, ni vídeos.

---

## Requisitos

- Android 8.0 o posterior en un procesador ARM64 (ARMv8.0-A o superior).
- GPU con Vulkan 1.1 o posterior.
- Una copia propia de **Need for Speed: Most Wanted (2005), Xbox 360, edición PAL España**, extraída y
  con `default.xex`, `NFS/` y `Movies/`. El APK se compila para ese ejecutable: con otra edición el juego
  se cierra al arrancar. Una ISO sin extraer o los archivos de PC/PS2 no sirven.
- Espacio en la memoria interna para el APK, la carpeta del juego y los archivos generados.

Probado en un **Samsung Galaxy A20** (Exynos 7884B, Mali-G71 MP2, Android 11) y en GPUs Mali, Adreno y
Xclipse de la gama alta actual.

### Mali Bifrost (G71, G72, G76)

Estas GPU limitan el número de *descriptor sets* enlazados a cuatro y no ofrecen *descriptor indexing*.
En esos dispositivos el launcher permite elegir el **driver MaliCompat**, que adapta el renderizador a ese
límite. En GPUs que sí soportan *descriptor indexing* se usa el driver del sistema.

---

## Instalación

1. **Descarga el APK** desde [Releases](https://github.com/CodeAbhi826/nfsmwrecomp-mali/releases).
2. **Concede permisos:** instala el APK, abre el launcher y acepta el acceso a todos los archivos.
3. **Copia el juego** en `Almacenamiento interno/nsfmw-androidevolved/`:

   ```text
   nsfmw-androidevolved/
   ├── default.xex
   ├── NFS/
   └── Movies/
   ```

4. **Genera los shaders:** pulsa **Jugar**. La primera vez se compila `nfsmw_shaders.nfsp` a partir del
   ejecutable; puede tardar unos minutos.
5. **Ajustes recomendados en Mali-G71:**
   - Resolución: `960x540` o `1280x720`
   - Límite de FPS: `30`
   - Renderizador: nativo (`--nfsmw_renderizador=nativo`)

---

## Herramientas AOT

Para reducir el trabajo en tiempo real en dispositivos lentos, los recursos se pueden convertir antes de
copiarlos:

### 1. Vídeo (WMV3 → H.264 MP4)

```bash
python3 tools/aot/transcode_movies.py /ruta/al/juego/Movies/
```

### 2. Texturas (ASTC 4×4)

Requiere [astcenc](https://github.com/ARM-software/astc-encoder):

```bash
python3 tools/aot/transcode_astc.py /ruta/texturas/ /ruta/salida_astc/ --block 4x4
```

### 3. Audio (XMA → Ogg/Opus)

Requiere `ffmpeg`:

```bash
python3 tools/aot/transcode_audio.py /ruta/audio/ /ruta/salida_audio/ --format ogg
```

---

## Compilar

Requisitos: Android SDK y NDK `28.2.13676358`, JDK 17+ y CMake 3.22.1+.

```bash
cd android
./gradlew assembleDebug
```

El APK queda en `android/app/build/outputs/apk/debug/app-debug.apk`.

El flujo de [GitHub Actions](.github/workflows/android.yml) compila **solo el launcher**: comprueba las
interfaces nativas y la parte Java, pero no incluye el código recompilado del juego ni las librerías del
motor, así que su APK no puede iniciar el juego. Los APK jugables se publican en
[Releases](https://github.com/CodeAbhi826/nfsmwrecomp-mali/releases).

---

## Créditos

- [codepdbh/nfsmw-android](https://github.com/codepdbh/nfsmw-android): launcher Android, integración con SDL3 y base del pipeline Vulkan.
- [StevensND/nfsmw-nx](https://github.com/StevensND/nfsmw-nx): la recompilación original para consola.
- [madelrandel-blip/NFSMW-Recompiled](https://github.com/madelrandel-blip/NFSMW-Recompiled): mapeo inicial de funciones y ganchos de decompilación.
- [ReXGlue SDK](https://github.com/rexglue/rexglue-sdk) / [Xenia](https://xenia.jp): primitivas del kernel de Xbox 360 y traducción del microcódigo de la GPU.
- [ARM Software](https://github.com/ARM-software/astc-encoder): codificador ASTC.

---

## Aviso legal

Proyecto educativo y no comercial, hecho para preservación técnica y compatibilidad de hardware.

- **Need for Speed** y **Need for Speed: Most Wanted** son marcas registradas de Electronic Arts Inc.
- **Xbox 360** es una marca registrada de Microsoft Corporation.
- Este repositorio no contiene recursos del juego, binarios del disco (`default.xex`) ni medios comerciales. Cada usuario debe aportar su propia copia legal.
