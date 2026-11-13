# Mantenimiento M.A

Sitio para mantenimiento de consolas y computadoras, con servidor Java y opiniones guardadas en JSON.

## Ejecutar

Necesitas JDK 17 o posterior. Abre la carpeta del proyecto en VS Code y ejecuta en la terminal:

```sh
java src/NexoServer.java
```

Después abre <http://localhost:3000>.

La galería antes/después está separada en <http://localhost:3000/galeria.html>; ya no aparece como sección en el inicio. El editor de fotos está en <http://localhost:3000/admin.html>, protegido con la contraseña configurada en `data/admin.json`. Allí se pueden sustituir las fotos de antes y después y las fotos de perfil de los técnicos; se aceptan JPG, PNG y WebP de hasta 20 MB. Cada cambio guarda la imagen en `public/images/` y actualiza las rutas en `public/site-content.json`. Las opiniones nuevas se muestran debajo del formulario y la página lista todas las opiniones guardadas.

La consulta técnica recoge equipo, problema, nombre y teléfono y guarda el aviso en `data/requests.json`. Los técnicos revisan las solicitudes en <http://localhost:3000/tecnicos.html>; la bandeja se actualiza cada 20 segundos mientras esté abierta. Usa la misma clave privada para acceder. Las consultas se conservan localmente, hasta 1.000 registros.

## Editar precios, fotos y perfiles

La información editable vive en `public/site-content.json`:

- En `services`, escribe cada tarifa en `price`, por ejemplo `"₡ 12 500"`. Los espacios en blanco muestran “Precio por definir”. Hay entradas separadas para PS4, PS5, Xbox, Nintendo Switch y PC/laptop.
- En `services`, puedes cambiar también `image` por la ruta de la ilustración de cada consola o laptop. Las nuevas ilustraciones SVG están en `public/images/`, tienen fondo transparente y se mueven suavemente al tocarlas. Puedes cambiar sus rutas en `site-content.json`.
- En `gallery`, las fotos se cambian desde el editor protegido en `/admin.html`. También puedes editar manualmente las rutas `before` y `after`, además del modelo, título y descripción de cada caso.
- En `team`, reemplaza `name`, `role` y `focus`. Las fotos de perfil se pueden cambiar desde el editor de administración. Los cargos iniciales son “Cofundador de soporte” y “Cofundador del sistema”.

Guarda los cambios y recarga el sitio. No tienes que modificar JavaScript para actualizar el contenido.

## Antes de publicar

Las nuevas opiniones se guardan en archivos separados dentro de `data/comments/` y permanecen allí hasta que su autor las borre. El Gmail se guarda de forma privada y solo aparece en la opinión pública si la persona activa la casilla de consentimiento al publicar. Los comentarios anteriores no se muestran con email porque no guardaban ese consentimiento. El navegador conserva una clave privada para mostrar “Eliminar mi opinión” al autor en el mismo dispositivo; borrar los datos del navegador elimina esa clave, por lo que conviene no borrar el almacenamiento del sitio si se quiere conservar la opción de eliminación. No se envían correos: el Gmail se recopila como dato privado del comentario. `data/comments.json` se conserva para leer opiniones antiguas.

Los precios iniciales de `public/site-content.json` son referencias editables en colones. Actualiza allí las tarifas y reemplaza las imágenes de muestra de la galería antes de publicar. Los botones de contacto y pago ya abren el WhatsApp del técnico al +506 8892 2886; para cambiarlo, edita el número en `public/index.html`. Las ilustraciones de producto se redibujaron tomando las referencias como guía; no son las capturas originales. El panel de técnicos requiere contraseña y debe publicarse únicamente detrás de HTTPS; el servidor de ejemplo está pensado para ejecución local.
