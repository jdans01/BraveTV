# Changelog

Todos los cambios notables de este proyecto se documentan en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es/1.0.0/).

---

## [2.0] — 2026-10

### Añadido
- Selección de video por video con D-pad Arriba/Abajo en resultados de búsqueda de YouTube (antes solo se podía navegar lateralmente entre los filtros)
- Navegación propia inyectada también en YouTube escritorio (no solo `/tv`), ya que el interstitial de "descargá la app" casi siempre redirige ahí
- Cada tarjeta de video se selecciona como una sola unidad (miniatura completa), sin caer en cada sub-elemento (avatar del canal, botón de subtítulos, menú de tres puntos)

### Cambiado
- ATRÁS ahora vuelve directo al menú principal desde YouTube, en vez de navegar sin fin por el historial interno de la SPA de YouTube
- La barra de URL solo entra en modo edición con un clic/OK explícito, ya no simplemente al pasar el foco con las flechas

### Corregido
- Choque con la política de seguridad Trusted Types de YouTube que rompía el script de navegación silenciosamente (`innerHTML` → `textContent`)
- Condición de carrera que podía dejar una página sin respuesta a las flechas de forma intermitente, sin ningún error visible
- `document.activeElement` no reflejaba el foco real dentro del Shadow DOM de las tarjetas de video de YouTube, rompiendo tanto el cálculo de "siguiente video" como la selección con OK/Enter
- Abrir un enlace desde "Recientes" perdía el modo YouTube, rompiendo el comportamiento de ATRÁS para esos casos
- El foco del WebView se perdía después de cualquier búsqueda, dejando las flechas sin respuesta

---

## [1.1.0] — 2024-10

### Añadido
- Pantalla de inicio con 4 modos de navegación (Brave Browser, Brave Privado, YouTube TV, YouTube Web)
- Animaciones de escala en cards al recibir foco con D-pad
- Transiciones de fade entre pantalla de inicio y navegador
- Historial reciente (últimas 8 URLs) persistido en SharedPreferences
- Contador en tiempo real de anuncios y rastreadores bloqueados
- Modo incógnito: limpia cookies, caché e historial al activarse
- Íconos circulares estéticos para cada modo de navegación
- Paleta de colores unificada (fondo `#0C0C14`, acento `#C084FC`)

### Cambiado
- **Brave Browser** ahora abre `search.brave.com`
- **Brave Privado** ahora abre `duckduckgo.com` en modo incógnito
- Cards del home usan un único drawable `card_mode.xml` con estados (focused/pressed/default)
- Reemplazados emojis por drawables circulares personalizados en los íconos de cards

### Corregido
- `SecurityException: MODE_WORLD_READABLE` causado por colisión de nombres de constantes de navegación con `Context.MODE_PRIVATE`

---

## [1.0.0] — 2024-09

### Añadido
- Proyecto inicial: WebView con bloqueador de anuncios
- Bloqueo de 30+ dominios publicitarios (AD_HOSTS)
- Bloqueo por patrones de URL (AD_URL_PATTERNS)
- Auto-skip de anuncios via JavaScript
- Inyección de CSS para ocultar elementos publicitarios
- Bypass de geo-restricciones con 10 regiones (VPN AUTO)
- Cambio automático de región al detectar bloqueo geo
- Selección manual de región (pulsación larga en botón VPN)
- Navegación por D-pad (mando a distancia)
- Scroll con D-pad en páginas web
- Barra de navegación con URL bar, atrás, adelante, reload, home
- Botón de búsqueda rápida en YouTube
- Auto-ocultación de navbar durante reproducción de video
- Íconos de lanzador (escudo con león) en 5 densidades
- CI/CD con GitHub Actions (genera APK en cada push)
- Compatibilidad con Android 5.0+ (API 21)
