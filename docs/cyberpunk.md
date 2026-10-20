# Flavor cyberpunk

El módulo `launcher/` tiene dos product flavors:

- `stock`: el launcher upstream, sin cambios.
- `cyberpunk`: variante con temas propios. Su `applicationId` es `dev.tleyton.cyberhome`, así que convive con el upstream en la misma TV. El namespace Java sigue siendo `com.gathin.tvhome`, por eso el componente de la activity es `dev.tleyton.cyberhome/com.gathin.tvhome.MainActivity`.

## Compilar

Desde `launcher/`:

```powershell
.\gradlew.bat assembleCyberpunkRelease
```

El APK queda en `launcher\app\build\outputs\apk\cyberpunk\release\app-cyberpunk-release.apk`.

## Instalar en la TV

Requisitos: depuración ADB activada en la TV, JDK 17 y el Android SDK (el script usa el `adb.exe` de `platform-tools`, en `%ANDROID_HOME%` o `%LOCALAPPDATA%\Android\Sdk`).

```powershell
.\launcher\build-and-install-cyberpunk.ps1 -TvHost 192.168.1.50
```

Opciones:

- `-StockLauncher <paquete>|none`: launcher stock a deshabilitar; por defecto se detecta desde la TV. `none` no deshabilita nada.
- `-NoBuild`: instala el APK ya compilado.
- `-TvHost` puede sustituirse por la variable de entorno `TV_HOST`.

El script compila, instala con `adb install -r`, concede el permiso de ubicación, arranca el launcher, deshabilita el stock (`pm disable-user`) y fija el nuevo como Home. Si al final Home no resuelve a `dev.tleyton.cyberhome`, rehabilita lo que deshabilitó y aborta.

En Google TV (launcher `com.google.android.apps.tv.launcherx`, como la TCL Smart TV Pro) el asistente de configuración `com.google.android.tungsten.setupwraith` también reclama Home con prioridad 1, y Android elige por prioridad antes de mirar la preferencia. Por eso el script lo deshabilita también (`-KeepSetupWizard` lo evita). Es reversible, ver abajo.

## Configuración por tema

El `config.json` por tema vive ahora en `/sdcard/Android/data/dev.tleyton.cyberhome/files/`.

## Volver al launcher stock

```powershell
adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
adb shell pm enable --user 0 com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx
```

(En un Android TV clásico el launcher stock es `com.google.android.tvlauncher` y no hay setupwraith que rehabilitar.)

## Desinstalar

```powershell
adb uninstall dev.tleyton.cyberhome
```

Hazlo después de volver al launcher stock, o la TV se quedará sin Home hasta que lo rehabilites.
