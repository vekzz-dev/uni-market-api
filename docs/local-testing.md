# Probar la API en local

Guía reproducible para levantar el proyecto en tu máquina y recorrer el flujo completo:
registro → confirmación → login → publicar → moderar → catálogo. Incluye cómo probar
**Resend de verdad** sin dejar de ver el token en el log.

Todo lo de aquí está verificado sobre PostgreSQL 18 + Podman en Linux. Con Docker, cambia
`podman` por `docker` y `podman compose` por `docker compose`.

---

## 1. Requisitos

| Herramienta | Para qué |
|---|---|
| Podman o Docker + Compose | Base de datos y API en contenedores |
| Java 21 + Maven (opcional) | Solo si prefieres arrancar la API desde IntelliJ o `mvn spring-boot:run` |
| `curl` y `jq` (opcional) | Flujo de humo desde terminal |

La imagen de la API es multi-stage: compila con Maven dentro del contenedor y el runtime
final es solo un JRE. No necesitas Maven para el camino de contenedores.

---

## 2. Configurar `.env`

El repo espera un `.env` en la raíz, ignorado por git.

```bash
cd uni-market-api
printf 'JWT_SECRETO=%s\n' "$(openssl rand -base64 48)" > .env
```

Ese es el mínimo. Para el modo de correo, añade:

```bash
# .env
NOTIFICACIONES_PROVEEDOR=resend+log     # log | resend | resend+log
RESEND_API_KEY=re_tu_clave
RESEND_REMITENTE=onboarding@resend.dev  # sin dominio verificado, este es el único remitente
NOTIFICACIONES_DESTINO_PRUEBA=tu-correo@tudominio.com  # opcional: ver abajo
```

| Modo | Qué hace |
|---|---|
| `log` (default) | Imprime el correo con el token en el log. No envía nada |
| `resend` | Envía por Resend. El token NO aparece en el log |
| `resend+log` | Envía por Resend **y** deja el token en el log (recomendado en local) |

> **Sin dominio verificado en Resend** solo puedes enviar desde `onboarding@resend.dev` y
> con entrega real únicamente al email de tu cuenta de Resend (y a las direcciones de
> prueba `delivered@resend.dev`). Cualquier otra dirección recibe un `403`: verás tres
> `WARN` en el log, pero **el token ya está impreso**, así que puedes confirmar la cuenta
> igual. Para usar `no-reply@tudominio.com` necesitas verificar el dominio en Resend.

### `NOTIFICACIONES_DESTINO_PRUEBA`: una sola bandeja para todo

Si quieres comprobar la entrega real sin depender de la dirección con la que registres,
define la bandeja que debe recibir **todos** los correos:

```bash
NOTIFICACIONES_DESTINO_PRUEBA=tu-correo@tudominio.com
```

Aunque registres `ana@uni.edu` o una dirección inventada, el correo va a esa bandeja y el
destinatario original se antepone en el asunto (`[para ana@uni.edu] Confirma tu cuenta…`),
así no pierdes el rastro. Vacío = comportamiento normal.

> Es una ayuda de desarrollo: en `prod` la app **no arranca** si la variable tiene valor,
> porque desviaría correos de usuarios reales.

`.env` nunca se commitea. Si una clave se expone (por ejemplo, pegada en un chat),
rótala en el panel del proveedor y actualiza el archivo.

---

## 3. Levantar el stack

```bash
podman compose up -d --build     # primera vez: compila la imagen (varios minutos)
podman compose up -d             # siguientes: arranque normal
```

Espera a ver `Started UniMarketApiApplication` en el log:

```bash
podman logs -f unimarket-api | grep -E "Flyway|Started UniMarket|Tomcat started"
```

Comprobación rápida de que responde y que la semilla cargó:

```bash
curl -s localhost:8080/v1/categorias | jq '.datos | length'   # 6
```

**Si cambiaste `.env`, el contenedor no se recrea solo.** Fuerza:

```bash
podman compose up -d --force-recreate api
```

### Alternativa: IntelliJ o terminal

```bash
podman compose up -d postgres
set -a && source .env && set +a
mvn spring-boot:run
```

En IntelliJ, carga `JWT_SECRETO` en *Environment variables* de la configuración de
ejecución. Si usas un secreto distinto al del contenedor, los tokens emitidos antes dejan
de validar.

---

## 4. Flujo de humo

### 4.1 Registro

```bash
curl -s -X POST localhost:8080/v1/usuarios \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana@uni.edu","nombre":"Ana Pérez","contrasena":"secreta123"}'
```

Responde `201`. El email de confirmación sale en el log en `dev`/`resend+log`.

### 4.2 PIN de confirmación desde el log

El correo no trae enlaces: trae un **PIN de 6 dígitos** (15 minutos, 5 intentos como máximo).

```bash
podman logs unimarket-api | grep -o 'es [0-9]\{6\}\.' | tail -1
```

Copia el PIN y confirma:

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST localhost:8080/v1/auth/confirmar-email \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana@uni.edu","pin":"PEGAR_PIN"}'    # 200
```

### 4.3 Login

```bash
curl -s -X POST localhost:8080/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana@uni.edu","contrasena":"secreta123"}'
```

Devuelve `accessToken` (15 min) y `refreshToken`. Guárdalo:

```bash
TOKEN=$(curl -s -X POST localhost:8080/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana@uni.edu","contrasena":"secreta123"}' | jq -r .accessToken)
```

> El límite de tasa por defecto es **5 intentos/minuto** en login, registro, reset y
> reenvío. Si repites mucho, verás `429`: espera o sube `RATE_LIMIT_INTENTOS`.

---

## 5. Roles para moderar

No hay usuarios sembrados: el primer moderador o admin se promueve por SQL. El cambio
surte efecto en el **siguiente request**, sin relogin (la BD es la autoridad del rol).

```bash
podman exec unimarket-pg psql -U unimarket -d unimarket -c \
  "UPDATE usuarios SET rol='moderador', fecha_verificacion_email=now() WHERE email='ana@uni.edu';"
```

Cambia `rol='admin'` para el panel de administración (incluye gestión de usuarios).

---

## 6. Publicar, moderar y ver en el catálogo

```bash
# Una imagen válida mínima (JPEG)
printf '\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xff\xd9' > /tmp/foto.jpg

# Publicar (nace pendiente, R5)
curl -s -X POST localhost:8080/v1/anuncios \
  -H "Authorization: Bearer $TOKEN" \
  -F 'nombre=Calculadora' -F 'precio=45.00' -F 'imagenes=@/tmp/foto.jpg;type=image/jpeg'
```

Con otro usuario moderador, aprobar:

```bash
curl -s -X POST "localhost:8080/v1/moderacion/anuncios/$ANUNCIO_ID/decisiones" \
  -H "Authorization: Bearer $TOKEN_MODERADOR" -H 'Content-Type: application/json' \
  -d '{"decision":"aprobar"}'
```

Y comprobar que aparece en el catálogo público:

```bash
curl -s 'localhost:8080/v1/anuncios?q=Calculadora'
```

Mientras está `pendiente`, el catálogo no lo muestra y el detalle público responde `404`.

---

## 7. Probar Resend de verdad

Con `NOTIFICACIONES_PROVEEDOR=resend+log` y credenciales válidas:

1. Registra una cuenta con una dirección de prueba:
   `delivered+prueba@resend.dev`.
2. Mira el log: debe aparecer el mensaje con el token y **ningún** `WARN`.

```bash
podman logs --since 5m unimarket-api | grep -iE "Correo transaccional|fallido|descartado"
```

Qué esperar según el caso:

| Situación | Log |
|---|---|
| Credenciales válidas, dirección de prueba | `Correo transaccional (dev): … token=…` y nada más |
| Destinatario real sin dominio verificado | El token + `3 × WARN` y `Correo descartado tras 3 intentos` |
| Clave inválida | `WARN … Resend respondió 401` en los 3 intentos |
| Sin `RESEND_API_KEY` con `resend+log` | La app **no arranca** y dice qué variable falta |

El log se escribe **antes** del envío: aunque el proveedor falle, el token sigue
disponible para confirmar la cuenta. En producción el modo `resend+log` está prohibido
porque imprimiría tokens crudos en los logs.

---

## 8. Problemas comunes

| Síntoma | Causa y solución |
|---|---|
| `define JWT_SECRETO en .env` | Falta el `.env` o la variable. Genera el secreto del paso 2 |
| Cambié `.env` y no surte efecto | Compose no recrea el contenedor: `podman compose up -d --force-recreate api` |
| `429` al hacer login | Límite de 5/min por IP y email. Espera un minuto o sube `RATE_LIMIT_INTENTOS` |
| El token no aparece en el log | Estás en modo `resend` (sin log) o en `prod`. Usa `log` o `resend+log` |
| `Broken pipe` con Podman rootless | El servicio necesita no tener timeout de inactividad; ver `odd/tasks/hardening-post-catalog.md` |
| Quiero empezar de cero | `podman compose down -v && podman compose up -d --build` (borra BD y medios) |
| El puerto 8080 está ocupado | `podman compose stop api` o cambia el mapeo en `compose.yaml` |

---

## 9. Variables relevantes

| Variable | Default | Uso |
|---|---|---|
| `JWT_SECRETO` | — (obligatoria) | Firma HS256 de los access tokens |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/unimarket` | Conexión a PostgreSQL |
| `NOTIFICACIONES_PROVEEDOR` | `log` | `log` · `resend` · `resend+log` |
| `RESEND_API_KEY` / `RESEND_REMITENTE` | — | Credenciales y remitente |
| `RATE_LIMIT_INTENTOS` | `5` | Intentos por minuto en login/registro/reset/reenvío |
| `MEDIA_PROVEEDOR` / `MEDIA_DIR` | `filesystem` / `~/.uni-market` | Almacenamiento de imágenes en dev |
| `SPRING_PROFILES_ACTIVE` | `dev` | `prod` exige credenciales de R2 y Resend y aborta si faltan |

Referencia completa: `README.md` y `src/main/resources/application.yml`.
