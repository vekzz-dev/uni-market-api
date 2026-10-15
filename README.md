# Uni Market — API

[![CI](https://github.com/vekzz-dev/uni-market-api/actions/workflows/ci.yml/badge.svg)](https://github.com/vekzz-dev/uni-market-api/actions/workflows/ci.yml)

Backend del marketplace universitario **Uni Market**: registro con confirmación de email, catálogo de anuncios con moderación previa, mensajería comprador–vendedor y panel de administración.

- **Stack**: Java 21 · Spring Boot 4.1 · Spring MVC · Spring Security (JWT HS256 + refresh rotativo) · PostgreSQL 18 · Flyway · Maven.
- **Contrato**: [`openapi.yaml`](openapi.yaml) es la fuente de verdad de la API (`/v1`). Los clientes web/móvil generan su código desde él en repositorios propios.
- **Diseño**: [`docs/prd.md`](docs/prd.md) (producto y reglas R1–R16), [`docs/database-design.md`](docs/database-design.md) (esquema), [`docs/system-design.md`](docs/system-design.md) (arquitectura y decisiones D1–D14).

---

## Requisitos

| Herramienta | Versión |
|---|---|
| JDK | 21 o superior (probado en 25) |
| Maven | 3.9+ |
| Contenedor | Podman o Docker — para la BD de desarrollo y para Testcontainers en las pruebas |

No necesitas instalar PostgreSQL: el `compose.yaml` lo levanta.

## Arranque local

### Paso 1 — Secreto y base de datos (una vez por sesión de trabajo)

```bash
# El secreto se genera UNA sola vez por máquina (ignorado por git):
printf 'JWT_SECRETO=%s\n' "$(openssl rand -base64 32)" > .env

podman compose up -d postgres
# Con Docker: docker compose up -d postgres
# Datos persistentes en el volumen del compose; `down`/`up` los conserva.
```

### Paso 2 — Arranca la API (elige tu modo)

**Modo A — IntelliJ (recomendado para programar)**

1. `cat .env` y copia el valor del secreto.
2. **Run → Edit Configurations → UniMarketApiApplication → Environment variables**: `JWT_SECRETO=` y pega el valor (solo la primera vez).
3. ▶ Run. Esperado en el log: perfil `dev` · Flyway al día · `Tomcat started on port 8080`.

**Modo B — Terminal**

```bash
set -a && source .env && set +a   # el MISMO secreto del .env (no generes otro)
mvn spring-boot:run
```

> En ambos casos la API queda en `http://localhost:8080/v1` y **el token de confirmación de email sale en el log de la app** (en IntelliJ: su consola).

### Paso 3 — Flujo de humo (registro → confirmación → login)

```bash
# Registro (201). El email de confirmación se imprime en el log de la app en dev:
#   · IntelliJ: la consola de la aplicación
#   · Compose:  podman logs unimarket-api | grep -i token
curl -s -X POST localhost:8080/v1/usuarios -H 'Content-Type: application/json' \
  -d '{"email":"ana@uni.edu","nombre":"Ana","contrasena":"secreta123"}'

# Copia el token del log y confirma:
curl -s 'localhost:8080/v1/auth/confirmar-email?token=PEGAR_TOKEN'

# Login → accessToken + refreshToken:
curl -s -X POST localhost:8080/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"ana@uni.edu","contrasena":"secreta123"}'

# Perfil autenticado:
curl -s localhost:8080/v1/usuarios/me -H "Authorization: Bearer PEGAR_ACCESS"
```

> **Correlación de logs**: todas las respuestas traen un header `X-Request-Id` (puedes enviar el tuyo) — úsalo para buscar el request en los logs: cada línea lleva su `[traceId]`.

> **Roles en dev** (moderador para aprobar anuncios, admin para el panel):
> ```bash
> podman exec unimarket-pg psql -U unimarket -d unimarket -c \
>   "UPDATE usuarios SET rol='moderador', fecha_verificacion_email=now() WHERE email='ana@uni.edu';"
> # Admin (todo lo del moderador + gestión de usuarios): cambia rol='admin'
> ```
> Con eso: `POST /v1/moderacion/anuncios/{id}/decisiones` con `{"decision":"aprobar"}`. El cambio surte efecto en el **siguiente request** (sin relogin: la BD es la autoridad del rol).

## Stack completo en contenedores (demo / entrega)

Para levantar **base de datos + API** sin IntelliJ ni Maven local:

```bash
podman compose up -d --build
# API en http://localhost:8080/v1 · el primer build tarda unos minutos
# (descarga dependencias dentro de la imagen; los siguientes son incrementales).
```

Notas:
- La imagen es multi-stage (`Dockerfile`): Maven compila y el runtime final es solo un JRE.
- Los medios subidos persisten en el volumen `unimarket-media`.
- Para volver al modo desarrollo (IntelliJ) libera el puerto: `podman compose stop api`.
- Apagar todo: `podman compose down` (los datos y los medios quedan en sus volúmenes).

## Perfiles de Spring

| Perfil | Adaptadores | Cuándo |
|---|---|---|
| **`dev`** (default) | Medios en filesystem, emails a consola | Desarrollo local (IntelliJ o compose sin tocar nada) |
| **`prod`** | Medios en **R2**, emails vía **Resend** | Despliegue real. Es **fail-fast**: si falta `JWT_SECRETO`, `RESEND_API_KEY`, `RESEND_REMITENTE` o cualquier clave de R2, la app **no arranca** y dice exactamente cuál falta |

```bash
# Simular el arranque de producción (fallará si no están las credenciales — es lo correcto):
SPRING_PROFILES_ACTIVE=prod podman compose up -d api

# Desarrollo normal (perfil dev implícito):
podman compose up -d api
```

El perfil activo también se fija con la variable `SPRING_PROFILES_ACTIVE` en IntelliJ si algún día lo necesitas.

## Pruebas

```bash
mvn clean verify
```

Ejecuta la suite completa (**193 tests**) contra **PostgreSQL 18 real** (Testcontainers), con gate de cobertura ≥ 80 % sobre líneas. Nota para Podman rootless: si ves `Broken pipe` contra el socket, el servicio API debe correr sin timeout de inactividad — ver `odd/tasks/hardening-post-catalog.md` (fix documentado con `--timeout 0`).

## Variables de entorno

| Variable | Default (dev) | Uso |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/unimarket` / `unimarket` | Conexión a PostgreSQL |
| `JWT_SECRETO` | *(vacío — requerido)* | Firma/validación HS256 de access tokens |
| `RATE_LIMIT_INTENTOS` | `5` | Intentos por minuto en login/registro/reset/reenvío |
| `CORS_ORIGENES` | localhost (solo dev) | Orígenes de navegador permitidos, separados por coma. **Prod va vacío por defecto** |
| `MEDIA_PROVEEDOR` | `filesystem` | `filesystem` (dev) o `r2` (prod) |
| `MEDIA_DIR` | `~/.uni-market` | Raíz del filesystem de medios en dev |
| `MEDIA_R2_ENDPOINT` / `MEDIA_R2_BUCKET` / `MEDIA_R2_ACCESS_KEY_ID` / `MEDIA_R2_SECRET_ACCESS_KEY` / `MEDIA_R2_URL_PUBLICA` | *(vacías)* | Cloudflare R2 (prod) |
| `NOTIFICACIONES_PROVEEDOR` | `log` | `log` (consola, dev) o `resend` (prod) |
| `RESEND_API_KEY` / `RESEND_REMITENTE` | *(vacías)* | Resend (prod) |
| `NOTIFICACIONES_COOLDOWN_REENVIO` | `2m` | Cooldown del reenvío de confirmación |
| `SPRING_PROFILES_ACTIVE` | `dev` | Perfil activo: `dev` o `prod` (prod es fail-fast con las credenciales de arriba) |

## Mapa de la API (`/v1`)

| Grupo | Rutas |
|---|---|
| Auth | `/auth/login` · `/auth/refresh` · `/auth/logout` · `/auth/confirmar-email` · `/auth/restablecer-contrasena` (+ `/confirmar`) · `/auth/reenviar-confirmacion` |
| Cuenta | `/usuarios` (registro) · `/usuarios/me` (+ avatar, contraseña, baja) |
| Catálogo | `/anuncios` (público, cursor) · `/anuncios/{id}` · `/categorias` (público) |
| Vendedor | `/anuncios` (multipart) · `/anuncios/{id}/contenido` · `/anuncios/{id}/imagenes` · `/anuncios/{id}/visibilidad` · `/anuncios/{id}/vendido` · `/mis-anuncios` |
| Mensajería | `/conversaciones` · `/mis-conversaciones` · `/conversaciones/{id}` · `/conversaciones/{id}/mensajes` · `/conversaciones/{id}/lecturas` |
| Moderación | `/moderacion/anuncios` · `/moderacion/anuncios/{id}/decisiones` |
| Admin | `/admin/usuarios` (+ `/rol`, `/habilitacion`) · `/categorias` (POST/PATCH/DELETE) |

## Estructura

```
src/main/java/com/unimarket/api/   shared · identity · catalog · moderation · messaging · admin · notifications · media
src/main/resources/db/migration/   Flyway V1–V3 (esquema, seed, tokens)
src/test/java/                     unit + slices + integración Testcontainers
docs/                              PRD · DBDD · SDD (fuente de verdad del diseño)
odd/tasks/                         registro de trabajo por feature (con evidencia)
openapi.yaml                       contrato de la API (validado en el build)
compose.yaml + Dockerfile          entorno local: BD (+ API opcional) en contenedores
```
