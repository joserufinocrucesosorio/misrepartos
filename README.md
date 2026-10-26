# MisRepartos

App Android para que un repartidor registre su propia jornada y genere evidencia en PDF.
Gratuita, sin cuentas, sin servidores y sin conexión a internet.

**Autor:** JoseRufino · **Licencia:** GPL-3.0

## Qué hace

- Registra los hitos de la jornada (recibo, recogida, entrega, km) leyendo **solo** la pantalla de la app de reparto mediante el servicio de accesibilidad. **No pulsa, no modifica y no envía nada.**
- Guarda todo **en tu móvil** y genera **PDF locales**: informe diario, expediente y factura.
- Los PDF se guardan en la **carpeta que tú elijas**, para que puedas borrarlos o moverlos a una tarjeta SD o al PC.

## Privacidad

- **Sin permiso de INTERNET**: la app no puede transmitir datos. Se puede comprobar con `aapt2 dump permissions`.
- Sin datos personales en el código: el nombre del repartidor y la zona son campos que rellena cada usuario.
- No hay telemetría ni analítica.

## Aviso de almacenamiento

Los informes ocupan espacio. Se recomienda guardarlos en **almacenamiento externo**
(tarjeta SD o carpeta compartida con el PC) y borrar del móvil los que ya no necesites.

## Instalación

1. Descarga el APK de la sección **Releases**.
2. Verifica el SHA-256 publicado junto al APK.
3. Permite la instalación de orígenes desconocidos y abre el fichero.
4. Activa el **servicio de accesibilidad** de MisRepartos cuando la app lo pida.

## Compilar

```bash
./gradlew assembleDebug      # APK de pruebas
./gradlew assembleRelease    # APK firmado (requiere keystore.properties)
```

## Descargo de responsabilidad

Software distribuido **tal cual**, sin garantía de ningún tipo. No está afiliado,
patrocinado ni respaldado por ninguna plataforma de reparto. El uso es
responsabilidad de quien lo instala.
