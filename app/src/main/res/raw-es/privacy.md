# Política de privacidad — Read Files Tech

_Última actualización: 26 de octubre de 2026 (versión 3.0.1)_

## En una frase

Read Files Tech no recopila, no transmite ni conserva ningún dato en un servidor: la aplicación no tiene permiso para acceder a Internet, y sus archivos solo salen de su teléfono cuando usted mismo los entrega a otra aplicación.

## Quién es el responsable

Read Files Tech la publica Patrice Haltaya (Files Tech), a título personal. Contacto: **contact@files-tech.com**. El código fuente es público: https://github.com/gitubpatrice/READ-FILES-TECH.

El desarrollador no recibe ningún dato de la aplicación: no tiene, por tanto, nada sobre usted que conservar, consultar o borrar. Solo le llega un correo que usted decida escribirle — «Informar de un error» le añade las versiones de la aplicación y de Android. Ese correo solo sirve para responderle; se conserva el tiempo del intercambio y después se borra. Puede solicitar el acceso a él o su supresión en la misma dirección, y reclamar ante la CNIL, la autoridad francesa de protección de datos (www.cnil.fr), si considera que no se respetan sus derechos.

## Lo que la aplicación no hace

- Sin publicidad, sin rastreadores, sin medición de audiencia, sin informes de fallos enviados.
- Sin cuenta, sin registro.
- **Sin permiso de Internet**: `INTERNET` y `ACCESS_NETWORK_STATE` se retiran del manifiesto de la aplicación, y su compilación rechaza cualquier versión que llevara uno de ellos. La aplicación no puede llegar a ningún servidor.
- **Sin servicios de Google**: ni servicios de Google Play, ni ML Kit, ni Firebase. El reconocimiento de texto (OCR) se hace en el teléfono, con Tesseract. La WebView que muestra las páginas HTML está configurada para no enviar estadísticas de uso a su fabricante, y la aplicación no pide su fuente de emojis a un servicio externo.
- La aplicación no hace una lista de sus aplicaciones. Solo comprueba si hay cuatro aplicaciones instaladas, para ofrecerle enviarles un archivo: PDF Tech, kDrive, Proton Drive y Google Drive.

## Lo que la aplicación lee, y por qué

Read Files Tech es un explorador y visor de archivos. Con su consentimiento — «Acceso a todos los archivos» a partir de Android 11, el permiso de almacenamiento antes —, puede leer y modificar todos los archivos del almacenamiento compartido del teléfono, tarjeta SD incluida. Solo lo hace para lo que usted le pide: explorar una carpeta, abrir un archivo, convertirlo, buscar, encontrar duplicados… Nunca confía a otra aplicación un archivo de las carpetas `Android/data` o `Android/obb` de otras aplicaciones.

## Lo que se conserva en su teléfono

- **Archivos recientes (ruta, nombre, tamaño, fecha, favorito), archivos quitados de esa lista, tema, carpeta de salida, compartir automáticamente** — preferencias privadas de la aplicación, sin cifrar.
- **Ajustes de la caja fuerte (sal, parámetros de derivación de la clave), contadores de fallos** — preferencias privadas de la aplicación, sin cifrar; ninguna contraseña.
- **Historial de los editores: las 10 últimas versiones de cada archivo modificado en el editor de código o en el editor CSV, con su ruta** — almacenamiento privado de la aplicación, **sin cifrar**.
- **Archivos de la caja fuerte** — almacenamiento privado de la aplicación, cifrados (ver más abajo).
- **Modelos del OCR** — almacenamiento privado de la aplicación, incluidos con la aplicación.
- **Copias temporales: un archivo de la caja fuerte abierto o compartido, un PDF protegido desbloqueado, un elemento de archivo comprimido compartido, las fotos del escáner** — caché privada de la aplicación, sin cifrar; borradas cuando ya no se necesitan o, como muy tarde, en el siguiente inicio.
- **Archivos creados por las herramientas: escaneos, conversiones, compresiones, imágenes sin EXIF, textos del OCR, copias de seguridad de la caja fuerte, extracciones** — carpeta `Files Tech` del almacenamiento compartido, o la que elija en Ajustes, sin cifrar, salvo las copias de seguridad de la caja fuerte.
- **Papelera: los archivos eliminados y su descripción (nombre, ruta de origen, tamaño, fecha)** — carpeta oculta `.RFT_Corbeille` en la raíz de cada volumen, sin cifrar; **nunca se vacía automáticamente**.

Los archivos del almacenamiento compartido — la carpeta `Files Tech`, la papelera — los puede leer cualquier otra aplicación a la que usted haya dado acceso a sus archivos.

Si la carpeta de los archivos creados rechaza la escritura («Acceso a todos los archivos» denegado, tarjeta retirada), la aplicación escribe en su propia carpeta del almacenamiento compartido (`Android/data/…/files/Files Tech`) y, si no, en su almacenamiento privado; los Ajustes lo indican, y también el diálogo de una copia de seguridad de la caja fuerte. Esas carpetas se borran con la aplicación, y el modo pánico no borra lo que contienen.

## La caja fuerte

- Cada archivo se cifra con **AES-256-GCM**, con una clave derivada de su contraseña mediante **Argon2id**. Una caja fuerte creada con una versión anterior conserva la derivación con la que se creó (PBKDF2, o un Argon2id más ligero). La contraseña nunca se guarda.
- **No se cifran**: el **nombre** de los archivos de la caja fuerte, su **tamaño**, su número y sus fechas, así como los ajustes de derivación de la clave.
- La caja fuerte se bloquea 30 segundos después de que usted salga de la aplicación — con el teléfono en reposo profundo, al despertar, y siempre antes de que la caja fuerte vuelva a mostrarse —, tras 3 minutos sin tocar la pantalla, y cuando sale de su pantalla. Tras 5 contraseñas incorrectas, cada intento espera más, hasta 30 minutos.
- Las pantallas de la caja fuerte, y los archivos de la caja fuerte abiertos en la aplicación, no se pueden capturar y no aparecen en la vista previa de las aplicaciones recientes.
- Una **copia de seguridad** `.rftvault` se cifra entera, nombres incluidos, con una contraseña de copia de seguridad distinta; se escribe en la carpeta de copias de seguridad de la caja fuerte, que el diálogo de la copia muestra antes: en el almacenamiento compartido o, si la carpeta de salida rechaza la escritura, en una carpeta de la aplicación, que se borra con ella.
- **Compartir (descifrado)** crea una copia sin cifrar temporal, borrada al bloquear y en el siguiente inicio. **Exportar** escribe una copia sin cifrar permanente en la carpeta que usted elija. Lo que haga la aplicación que recibe un archivo ya no depende de Read Files Tech.

## Cuándo sale algo de su teléfono

Siempre por una acción suya, y hacia la aplicación que usted elija:

- **Compartir**, **Abrir con**: el archivo se entrega a la aplicación elegida, solo para lectura. Android le deja leer
  ese archivo, con ese nombre, mientras su pantalla siga abierta: si usted lo modifica mientras tanto, puede leer la
  nueva versión.
- **Enviar** a kDrive, Google Drive o Proton Drive, si están instalados.
- **Editar en PDF Tech**: el acceso de escritura solo se da si la aplicación instalada lleva la firma de PDF Tech.
- **Compartir automáticamente tras crear**, si activa este ajuste (desactivado por defecto).
- **Hacer una foto** para el escáner o el OCR: la aplicación de cámara del teléfono escribe la foto en un archivo que Read Files Tech le abre solo para eso.
- **Enlaces** — página de versiones, sitio web, correo —: se abren en su navegador o en su aplicación de correo, que se conectan por sí mismos. «Informar de un error» prepara un correo que usted revisa y envía.
- **Enlaces de un documento**: un enlace que usted toca en una página HTML o en un archivo Markdown se abre en su navegador o, para una dirección de correo, en su aplicación de correo, que se conectan por sí mismos; una página HTML muestra antes la dirección y pide confirmación.

El visor HTML muestra una página sin JavaScript y sin ninguna conexión: solo se cargan los archivos de la carpeta de la página.

## Modo pánico

Desde Ajustes, tras una confirmación, el modo pánico borra de inmediato: la caja fuerte y sus ajustes, todas las preferencias (archivos recientes incluidos), toda la caché, el historial de los editores, las extracciones antiguas y **la papelera** en todos los volúmenes. No borra: sus archivos, la carpeta `Files Tech` (copias de seguridad de la caja fuerte incluidas), los archivos exportados de la caja fuerte, las copias ya entregadas a otras aplicaciones, ni el portapapeles. El borrado elimina los archivos sin sobrescribir su contenido.

## Copia de seguridad de Android

La copia de seguridad de Android — en la nube o de un teléfono a otro — está desactivada para los datos de la aplicación. Los archivos del almacenamiento compartido no forman parte de ella: una herramienta que copie su almacenamiento puede llevárselos.

## Permisos de Android

| Permiso | Por qué |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` (Android 11 y posteriores) | El acceso a todos los archivos: el explorador |
| `READ_EXTERNAL_STORAGE` (hasta Android 12), `WRITE_EXTERNAL_STORAGE` (hasta Android 10) | Lo mismo en las versiones anteriores |
| `REQUEST_INSTALL_PACKAGES` | Entregar un APK tocado en el explorador al instalador de Android, que pide su propia confirmación |
| `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Añadido por una biblioteca de Android, interno a la aplicación |

Ni cámara, ni ubicación, ni contactos, ni micrófono, ni notificaciones, ni Internet.

## Registro del sistema

Cuando una operación falla, la aplicación escribe una sola línea en el registro del sistema: el tipo del error, sin nombre de archivo ni contenido. La biblioteca que lee los PDF y la WebView del sistema pueden añadir sus propios avisos, sin el contenido de las páginas, y Android anota en él un posible cierre inesperado de la aplicación; la aplicación no envía este registro a ningún sitio.

## Datos de las versiones anteriores

La versión 3.0.0 retoma en su sitio los datos de las versiones 2.x — preferencias, caja fuerte, papelera, historial —: nada se envía ni se copia a otro lugar, salvo las extracciones antiguas, que la aplicación propone una vez mover a la carpeta `Extractions` de los archivos que crea, o borrar: nada se mueve ni se borra sin su consentimiento.

## Sus derechos

Sus datos están en su teléfono, bajo su control: puede borrarlos desde la aplicación (archivos, papelera, caja fuerte, modo pánico) o desinstalándola. La desinstalación borra los datos privados de la aplicación, pero no la carpeta `Files Tech` ni la papelera, que están en el almacenamiento compartido. Para cualquier pregunta: **contact@files-tech.com**.

## Menores

La aplicación no está dirigida especialmente a menores; no contiene publicidad ni elaboración de perfiles.

## Cambios

Esta política puede evolucionar con la aplicación; la fecha del principio indica su versión.

## Idioma

Esta política se redactó en francés; las versiones en otros idiomas son traducciones. En caso de diferencia, **prevalece la versión francesa**.

---

Read Files Tech forma parte de la suite **Files Tech**, publicada por **Patrice Haltaya**.
