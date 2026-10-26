# Registro de Jornada Glovo — APK MVP (0.1.0-mvp)

App Android del proyecto. **APK compilado y firmado (debug) el 10/09/2026.**
Ruta: `glovo-app\apk\RegistroJornadaGlovo-0.1.0-mvp-debug.apk` (3,2 MB).
Proyecto: `glovo-app\android-app\` · paquete `online.soymasdigital.jornada` · minSdk 26 (Android 8+) · targetSdk 35.

## Qué hace este MVP
| Módulo | Estado MVP |
|---|---|
| **Cartera (snapshot inicio/fin)** con **BLOQUEO DURO** de cierre de jornada + vía manual siempre disponible | ✅ |
| **Movimiento del día** (saldo_fin − saldo_inicio) en el resumen y en el expediente | ✅ |
| **Cliente ausente**: importe total, tipo de pago, «¿cobraste?», **cierre por acción humana** (yo / la app / soporte) | ✅ |
| Notificación persistente con botones **CARTERA** y **CLIENTE AUSENTE** (servicio en primer plano) | ✅ |
| **Servicio de Accesibilidad** que detecta pantallas de la app de reparto/Maps y registra evidencias con hora real (solo observa) | ✅ |
| **Expediente PDF** generado en el móvil (APIs del sistema, sin dependencias) | ✅ |
| Base de datos local `laboral.db` (jornadas, snapshots, incidencias, evidencias) | ✅ |
| OCR on-device (ML Kit) + captura automática (MediaProjection) | ⏳ F2 |
| GPS/km reales + geofence + gastos moto + `mercado.db` cifrada + export WiFi al PC | ⏳ F2 |

## Instalar en el móvil
**Opción A (sin cables):** copia el APK al móvil (USB, Drive, WhatsApp a ti mismo), ábrelo y acepta
*«Instalar apps desconocidas»* para la app de archivos.
**Opción B (con USB y depuración activada):**
```powershell
C:\Users\apoya\Android\sdk\platform-tools\adb.exe install -r "C:\Users\apoya\.openclaw\workspace\glovo-app\apk\RegistroJornadaGlovo-0.1.0-mvp-debug.apk"
```

**Después de instalar:**
1. Abre la app y **acepta las notificaciones**.
2. **Ajustes → Accesibilidad → Registro de Jornada Glovo → Activar** (o desde el botón 5 de la app).
3. Verás la notificación fija con **CARTERA** y **CLIENTE AUSENTE**.

## Compilar
```powershell
$env:JAVA_HOME='C:\Users\apoya\tools\jdk17'
$env:ANDROID_HOME='C:\Users\apoya\Android\sdk'
cd C:\Users\apoya\.openclaw\workspace\glovo-app\android-app
.\gradlew.bat assembleDebug        # -> app\build\outputs\apk\debug\app-debug.apk
```

## Toolchain instalado (10/09/2026, sin permisos de administrador)
| Pieza | Ruta / versión |
|---|---|
| JDK | `C:\Users\apoya\tools\jdk17` → Temurin **17.0.20.1+1** |
| Android SDK | `C:\Users\apoya\Android\sdk` (cmdline-tools `latest`) |
| Paquetes SDK | `platform-tools 37.0.1`, `platforms;android-35`, `build-tools;35.0.0` (Gradle instaló además 34.0.0) |
| Gradle | `C:\Users\apoya\tools\gradle-8.13` (+ wrapper del proyecto) |
| AGP / Kotlin | 8.7.3 / 2.0.21 |
| Variables de usuario | `JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `PATH` |
| Scripts de instalación | `glovo-app\toolchain\install_toolchain.ps1`, `setup_sdk.ps1` (+ logs) |

## Notas
- El APK está firmado con la **clave debug** → vale para probar en tu móvil; para distribución a los
  1.224 riders habrá que crear una **clave de release** propia y firmar el APK de release.
- La app **no interactúa** con la app de reparto: solo observa pantallas (Accesibilidad) y registra datos propios.
- Pendiente de portar al móvil: el parser validado (los mismos PNG sirven de regresión) y las reglas de
  conciliación de efectivo que ya funcionan en el motor de PC (`mvp\engine.py`).
