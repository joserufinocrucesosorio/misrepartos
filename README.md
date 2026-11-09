# MisRepartos

App Android para que un repartidor registre su propia jornada y genere evidencia en PDF.
Gratuita, sin cuentas, sin servidores y sin conexión a internet.

**Versión:** 2.1.1 · **Autor:** JoseRufino · **Licencia:** GPL-3.0

📘 **[Guía de uso completa](GUIA.md)**

## Qué hace

- Registra los hitos de la jornada (recibo, recogida, entrega, km) leyendo **solo** la pantalla de la app de reparto mediante el servicio de accesibilidad. **No pulsa, no modifica y no envía nada.**
- Genera **PDF locales**: informe diario, expediente y factura.
- **Justificación de un aviso, sanción o suspensión** (novedad en 2.1.0): se indican los números de pedido y la app redacta el escrito con los datos reales de la jornada, insertando las capturas del momento.
- Los PDF se guardan en la **carpeta que tú elijas**, para que puedas borrarlos o moverlos a una tarjeta SD o al PC.

## Privacidad

- **Sin permiso de INTERNET**: la app no puede transmitir datos. Se puede comprobar con `aapt2 dump permissions`.
- Sin datos personales en el código: el nombre del repartidor y la zona son campos que rellena cada usuario.
- No hay telemetría ni analítica.

## Descarga

Solo se publica la **última versión** en la sección **Releases**:
https://github.com/joserufinocrucesosorio/misrepartos/releases/latest

Verifica siempre el **SHA-256** publicado junto al APK.

## Aviso de almacenamiento

Los informes ocupan espacio. Se recomienda guardarlos en **almacenamiento externo**
(tarjeta SD o carpeta compartida con el PC) y borrar del móvil los que ya no necesites.

La app guarda una copia reducida de las capturas de cada jornada durante **30 días**
(máximo 120 al día, 400 MB en total) para poder incluirlas en las justificaciones.

## Compilar

```bash
./gradlew assembleDebug      # APK de pruebas
./gradlew assembleRelease    # APK firmado (requiere keystore.properties)
```

## Descargo de responsabilidad

Software distribuido **tal cual**, sin garantía de ningún tipo. No está afiliado,
patrocinado ni respaldado por ninguna plataforma de reparto. El uso es
responsabilidad de quien lo instala.
