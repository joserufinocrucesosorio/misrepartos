# Guía de uso — MisRepartos 2.1.1

Guía correspondiente a la versión **2.1.1 (versionCode 58)**.

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

La carpeta elegida se recuerda. Todos los PDF se guardan ahí.

## 3. Uso diario

1. Abre la jornada al empezar a trabajar.
2. Trabaja con normalidad: la app registra recibo, ruta, llegada al local, recogida, llegada al cliente, entrega y kilómetros.
3. Al cerrar la jornada se genera el **informe del día en PDF** automáticamente.

## 4. Justificación de un aviso, sanción o suspensión

**Novedad de la 2.1.1.** Botón **«Justificación de un aviso o sanción (PDF)»**.

| Campo | Qué rellenar |
|---|---|
| Tipo | Llamada de atención / sanción / suspensión |
| Fecha y hora del aviso | Las de la carta |
| Nº de pedidos | Uno o varios *Order ID*, separados por comas o saltos de línea |
| Referencia / expediente | Opcional |
| Datos del trabajador | Nombre, DNI/NIE y empresa (se guardan la primera vez) |

La app busca esos pedidos en su base de datos y redacta el documento con el formato habitual: cabecera, tabla de datos, **RESUMEN** con horas exactas y **exposición de hechos** numerada, insertando las capturas del momento en que ocurrieron.

El PDF se guarda en la carpeta de informes y se puede **compartir** desde la propia app.

## 5. Capturas y almacenamiento

- La app guarda una **copia reducida** de las capturas de cada jornada (máximo 120 al día, JPEG 720 px).
- Esas copias se conservan **30 días** y ocupan como máximo 400 MB; después se borran solas.
- **Recomendación:** guarda los informes en almacenamiento externo (tarjeta SD o carpeta compartida con el PC) y borra del móvil los que ya no necesites.

## 6. Privacidad

- **Sin permiso de INTERNET**: la app no puede enviar datos a ningún servidor.
- Sin datos personales en el código: el nombre y la zona los pone cada usuario.
- Sin telemetría ni analítica.

## 7. Problemas frecuentes

| Problema | Solución |
|---|---|
| No aparecen las capturas en la justificación | Las capturas caducan a los 30 días; genera la justificación cuanto antes |
| El PDF no se guarda | Comprueba que la carpeta elegida sigue existiendo |
| Los datos de un pedido salen `s/d` | La app no registró ese dato; no se inventa |
| No detecta la jornada | Revisa que el servicio de accesibilidad siga activo |

## 8. Modelo de datos y soporte

- Los datos se guardan solo en el teléfono, en una base de datos privada de la app.
- Al desinstalar se borra todo.

Autor: **JoseRufino** · Licencia GPL-3.0 · Versión 2.1.1
