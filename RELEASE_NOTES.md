## 🦁 BraveTV Browser v2.1

Navegador para Android TV con bloqueador de anuncios integrado, bypass de geo-restricciones y modo privado.

---

### 📦 Instalación

1. Descarga **`BraveTVBrowser.apk`** desde los assets de abajo
2. Transfiere el APK a tu Android TV (USB, ADB o Send Files to TV)
3. Instala desde el explorador de archivos del TV
4. Si el TV lo pide, activa **Ajustes → Seguridad → Fuentes desconocidas**

> Requiere Android 5.0+ (API 21). A partir de esta versión, las apps ya instaladas reciben un
> aviso de actualización dentro de la propia app — no hace falta volver a descargar a mano.

---

### ✨ Novedades v2.1

- **Actualizaciones dentro de la app**: al abrir, chequea si hay una versión más nueva en
  GitHub Releases y ofrece descargarla e instalarla sin salir del navegador. A partir de esta
  versión todas las builds comparten una firma estable, así que instalar encima ya no borra tus
  datos (páginas recientes, región de VPN, calidad de video).
- **Botón de calidad de video** en la pantalla de inicio — tope de resolución según el tamaño de
  tu TV (Auto / 1080p / 720p / 480p), para que no se entrecorte en pantallas chicas o con
  decodificador débil. De paso, dejó de forzar un cambio de calidad cada 4 segundos sin
  necesidad (causaba micro-cortes por re-buffer).
- **Navegación D-pad más robusta** en resultados de búsqueda: ya no dependía de que la imagen
  del resultado estuviera cargada para poder seleccionarlo.
- **Ya no se cierra la app** al entrar a sitios con redes de anuncios que redirigen a esquemas
  como `intent://`/`market://` (Cuevana y similares).
- **Bloqueo de redirecciones de anuncios/pop-under**: si una página intenta saltar a otro
  dominio sin que hayas tocado nada, se bloquea y te quedás donde estabas.
- **Modo puntero** arreglado: ya no se queda "atascado" al activarlo ni se pierde el control al
  entrar a pantalla completa de video.
- **Pantalla gris de YouTube antes de un anuncio** reemplazada por la miniatura del propio
  video mientras carga, en vez de un cuadro gris sin contexto.
- **Bloqueador de anuncios/rastreadores de propósito general** (no solo YouTube): la lista
  pasó de ~30 dominios a más de 111,000, para que Brave Search, DuckDuckGo y cualquier sitio
  se beneficien del bloqueo, no solo los modos de YouTube.

### ✨ Novedades v2.0

- **Selección de video por video** con Arriba/Abajo en resultados de búsqueda de YouTube — antes el D-pad solo se movía entre los filtros de arriba y quedaba atascado ahí
- Cada tarjeta de video se selecciona **completa**, no por pedacitos (avatar, botón de subtítulos, menú de tres puntos)
- **ATRÁS vuelve directo al menú** desde YouTube, en vez de navegar sin fin por el historial interno de la app
- Corregidos varios bugs de fondo que rompían el D-pad de forma intermitente en YouTube: choque con la política Trusted Types, una condición de carrera que dejaba páginas sin respuesta a las flechas, y un problema con el foco dentro del Shadow DOM de las tarjetas de video
- La barra de URL ya no entra en modo edición solo con pasar el foco — requiere un clic/OK explícito

### 🕒 Novedades v1.1.0

- **Pantalla de inicio** con 4 modos de navegación y animaciones de D-pad
- **Brave Browser** → abre `search.brave.com`
- **Brave Privado** → abre `duckduckgo.com` en modo incógnito
- **Contador en tiempo real** de anuncios y rastreadores bloqueados
- Paleta de colores unificada (fondo oscuro `#0C0C14`, acento púrpura `#C084FC`)
- Íconos circulares personalizados por modo (reemplaza emojis)
- Transiciones con fade entre pantalla de inicio y navegador
- Historial reciente (últimas 8 URLs)
- Barra de nav se oculta automáticamente durante reproducción

### 🛡️ Funciones de bloqueo

- Bloquea **30+ dominios** publicitarios (DoubleClick, Google Ads, imasdk…)
- Auto-skip de anuncios vía JavaScript
- Ocultación de banners/overlays vía inyección CSS

### 🌍 VPN AUTO

- Bypass automático de geo-restricciones con 10 regiones
- Cambio de región automático al detectar bloqueo de YouTube
- Selección manual con pulsación larga en el botón VPN

---

### ☕ Apoya el proyecto

| Red | Dirección |
|-----|-----------|
| **Bitcoin (BTC)** | `bc1qa78yurqt2wsuc7nglnpwc867zxgjtcj0ztdzq0` |
| **Solana (SOL)** | `84e1VXoQx5AVdBH29CERnhveNEAQKf1CnFFJxrY1rwSF` |
