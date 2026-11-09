# Guía de uso — MisRepartos 2.1.2

Guía correspondiente a la versión **2.1.2 (versionCode 59)**.

## 1. Instalación

1. Descarga el APK desde la sección **Releases** del repositorio.
2. Verifica el **SHA-256** publicado junto al APK.
3. Abre el fichero descargado y permite **instalar aplicaciones de origen desconocido** para tu navegador o gestor de archivos.
4. Instala y abre **MisRepartos**.
5. Ajustes → Accesibilidad → **MisRepartos** → Activar. La app **solo lee** la pantalla de la app de reparto: no pulsa nada.

## 2. Primera configuración

| Ajuste | Dónde |
|---|---|
| Nombre del repartidor y zona | Ajustes de la app |
| **Carpeta de informes** | Botón «Elegir carpeta de informes» (selector del sistema) |

La carpeta elegida se recuerda. Todos los PDF y las copias de seguridad se guardan ahí.

## 3. Uso diario

1. Abre la jornada al empezar a trabajar.
2. Trabaja con normalidad: la app registra recibo, ruta, llegada al local, recogida, llegada al cliente, entrega y kilómetros.
3. Al cerrar la jornada se genera el **informe del día en PDF** automáticamente.

## 4. Justificación de un aviso, sanción o suspensión

Botón **«Justificación de un aviso o sanción (PDF)»** (desde la 2.1.0).

| Campo | Qué rellenar |
|---|---|
| Tipo | Llamada de atención / sanción / suspensión |
| Fecha y hora del aviso | Las de la carta |
| Nº de pedidos | Uno o varios *Order ID*, separados por comas o saltos de línea |
| Referencia / expediente | Opcional |
| Datos del trabajador | Nombre, DNI/NIE y empresa (se guardan la primera vez) |

La app busca esos pedidos en su base de datos y redacta el documento con el formato habitual: cabecera, tabla de datos, **RESUMEN** con horas exactas y **exposición de hechos** numerada, insertando las capturas del momento en que ocurrieron.

## 5. Copia de seguridad (novedad en 2.1.2)

### Exportar
Botón **«Exportar copia de seguridad»**. Genera un ZIP con:

| Contenido | Detalle |
|---|---|
| `laboral.db` | Toda tu jornada: pedidos, horas y kilómetros |
| `capturas_keep/` | Las capturas guardadas de los últimos 30 días |
| `INFO.txt` | Versión de la app, fecha y número de jornadas |

Se guarda en tu carpeta de informes con el nombre `MisRepartos_backup_AAAA-MM-DD_HHMM.zip` y puedes compartirlo (correo, Drive, WhatsApp…).

### Importar
Botón **«Importar copia de seguridad»**. Elige el ZIP y confirma.

1. Antes de sustituir nada, la app crea **automáticamente** una copia del estado actual (`backup_antes_de_importar.zip`).
2. Valida el ZIP y te dice cuántas jornadas y capturas trae frente a las que tienes.
3. Sustituye los datos y reinicia la app.

> **Aviso:** importar **reemplaza** todos los datos actuales. Si el ZIP no contiene `laboral.db`, la app no cambia nada.

**Para qué sirve:** cambiar de teléfono, pasar del APK de pruebas al oficial o hacer una copia periódica sin depender del PC.

## 6. Capturas y almacenamiento

- La app guarda una **copia reducida** de las capturas de cada jornada (máximo 120 al día, JPEG 720 px).
- Esas copias se conservan **30 días** y ocupan como máximo 400 MB; después se borran solas.
- **Recomendación:** guarda los informes en almacenamiento externo (tarjeta SD o carpeta compartida con el PC) y borra del móvil los que ya no necesites.

## 7. Privacidad

- **Sin permiso de INTERNET**: la app no puede enviar datos a ningún servidor.
- Sin datos personales en el código: el nombre y la zona los pone cada usuario.
- Sin telemetría ni analítica.

## 8. Problemas frecuentes

| Problema | Solución |
|---|---|
| No aparecen las capturas en la justificación | Las capturas caducan a los 30 días; genera la justificación cuanto antes |
| El PDF no se guarda | Comprueba que la carpeta elegida sigue existiendo |
| Los datos de un pedido salen `s/d` | La app no registró ese dato; no se inventa |
| No detecta la jornada | Revisa que el servicio de accesibilidad siga activo |
| He importado una copia y quiero volver atrás | Importa el `backup_antes_de_importar.zip` que se creó automáticamente |

## 9. Cambios de esta versión

| Versión | Novedad |
|---|---|
| 2.0.1 | Informes PDF locales, sin INTERNET |
| 2.1.0 | Justificación de avisos y sanciones |
| 2.1.1 | Archivo propio de capturas (30 días) |
| **2.1.2** | **Exportar e importar copia de seguridad** |

Autor: **JoseRufino** · Licencia GPL-3.0 · Versión 2.1.2
