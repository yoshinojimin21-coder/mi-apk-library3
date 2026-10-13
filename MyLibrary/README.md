# My library (Android)

Libro electrónico (.epub) + reproductor MP3 offline, con estética Apple de los 2000 (Aqua / iPod / metal cepillado), modo oscuro y botón único para cambiar de modo.

## Cómo compilarla
1. Instala **Android Studio** (Ladybug o más reciente).
2. `File > Open` y elige la carpeta `MyLibrary`. Deja que sincronice Gradle.
3. Conecta tu móvil (depuración USB) o abre un emulador y pulsa **Run ▶**.
4. Para un APK/AAB de lanzamiento: `Build > Generate Signed Bundle / APK` (necesitarás tu propia clave de firma).

## Uso
- Botón de arriba a la izquierda: cambia entre **Libros** y **Música**.
- Botón de arriba a la derecha: modo claro / oscuro.
- Libros: «Añadir libros» → elige .epub. Toca los lados de la pantalla (o desliza) para pasar página; toca el centro para mostrar/ocultar la barra (A−/A+, capítulos, tema).
- Música: «Añadir MP3». Sigue sonando con la pantalla apagada y desde la notificación.
- Al reabrir, vuelves al mismo modo, libro, página, canción y minuto.

## Seguridad
- Sin permiso INTERNET: la app no puede enviar ni recibir nada por red.
- Copias de archivos en almacenamiento privado (sandbox); sin permisos de almacenamiento (usa el selector del sistema).
- Se valida el contenido (EPUB = zip con container.xml; MP3 = cabecera ID3/sync), tope de tamaño y protección contra rutas maliciosas.
- WebView del lector con JavaScript desactivado, sin acceso a archivos, y solo sirve contenido del propio libro.
- allowBackup desactivado, servicio no exportado, R8 activado en release.

## Lectura por capítulos
El libro se divide en capítulos según su índice (NCX o nav de EPUB3); cada capítulo es un texto continuo que se desliza hacia abajo, sin páginas. Al final aparecen los botones Anterior / Siguiente, y el índice (☰) salta a cualquier capítulo. Si el libro no trae índice, se divide en partes de ~120 KB.
