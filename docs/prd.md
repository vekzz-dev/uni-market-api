# PRD — Uni Market (marketplace universitario)

| Campo | Valor |
|---|---|
| Estado | Aprobado — implementado y verificado |
| Última actualización | 2026-10-14 (aceptación del §8: evidencia en `odd/tasks/aceptacion-final.md`) |
| Fecha | 2026-10-09 |
| Versión | 2.0 (documento autónomo, sin dependencias externas) |
| Audiencia | Equipo de producto, diseño, backend y clientes (web/móvil) |
| Producto | Uni Market — marketplace de compraventa entre estudiantes de campus universitario |
| Dueño | Propietario del proyecto (auto-designado el 2026-10-10) |

> **Cómo leer este PRD**: define el *por qué* (problema, usuarios, alcance), las reglas de negocio a nivel de producto y los criterios de aceptación. Cada regla se enuncia completa en este documento; no remite a fuentes externas.

---

## 1. Problema y oportunidad

Los estudiantes de un campus compran y venden entre sí (libros, electrónica, ropa, material deportivo) por canales informales: grupos de chat sin catálogo, sin moderación y sin historial. Eso produce tres problemas concretos:

1. **No hay confianza**: cualquiera publica lo que sea, sin revisión previa.
2. **No hay descubrimiento**: no existe un catálogo buscable por categoría, precio o texto.
3. **No hay trazabilidad**: la negociación queda perdida en chats personales.

El **marketplace universitario** es una plataforma de comercio entre estudiantes con **moderación de anuncios por revisión previa**, catálogo público consultable y chat comprador–vendedor por anuncio. Se compone de una API REST única versionada bajo `/v1`, una aplicación web y una aplicación móvil que la consumen con el mismo cliente generado desde el contrato de la API. Las aplicaciones web y móvil viven en repositorios propios, fuera de este repositorio backend.

## 2. Objetivos y no-objetivos

### Objetivos

| # | Objetivo |
|---|---|
| O1 | Que un estudiante se registre, confirme su email y gestione su perfil de forma autónoma |
| O2 | Que todo anuncio pase por revisión antes de ser público, sin excepción de rol |
| O3 | Un catálogo público buscable (texto + categoría), paginado y estable ante inserciones |
| O4 | Que comprador y vendedor coordinen por chat con control de mensajes no leídos |
| O5 | Herramientas de moderación y administración auditables (quién decidió, cuándo, por qué) |
| O6 | Una sola fuente de verdad de API que evite que web y móvil diverjan (contract-first) |
| O7 | Una base de datos cuyas invariantes críticas estén impuestas por el esquema, no solo por código |

### No-objetivos (alcance explícito)

- **No es una tienda con pagos**: no hay pasarela ni checkout; el precio es informativo y el trato se cierra por mensajería.
- **No es chat en tiempo real**: sin WebSocket/STOMP ni push; el contrato del envío es la respuesta HTTP, con refresco bajo demanda o polling del cliente.
- **No es un servicio de notificaciones push**: el email es transaccional y cubre solo confirmación de registro y restablecimiento de contraseña.
- **No restringe el dominio del correo**: se acepta cualquier email sintácticamente válido, sin allow-list de dominio institucional.
- **No es un editor de mensajes**: el historial es inmutable; no hay edición ni borrado de mensajes ni de conversaciones.
- **No administra instituciones**: no gestiona aulas, cursos, ni acreditación académica; solo la vida comercial de los estudiantes.

## 3. Usuarios y roles

Jerarquía `miembro` < `moderador` < `admin` con herencia de permisos (un rol superior puede todo lo de su inferior). Cada usuario tiene exactamente un rol, almacenado en la columna `usuarios.rol` con dominio cerrado y default `miembro`. El rol lo deriva el servidor de la fila del usuario autenticado y nunca de datos enviados por el cliente.

| Rol | Puede hacer | No puede hacer |
|---|---|---|
| **`miembro`** (rol base de todo registro) | Leer el catálogo público; publicar anuncios (hasta 5 imágenes); gestionar solo SUS anuncios (editar, ocultar/mostrar, marcar vendido, dar de baja); abrir conversaciones y chatear; gestionar perfil, avatar y cuenta (baja con soft-delete) | Moderar; cambiar roles; modificar anuncios ajenos; aprobar su propio anuncio |
| **`moderador`** | Todo lo de `miembro` + cola de moderación (aprobar/rechazar/retraer anuncios con motivo obligatorio) + CRUD de categorías | Cambiar roles; aprobar, rechazar o retraer UN ANUNCIO PROPIO (no auto-aprobación) |
| **`admin`** | Todo lo de `moderador` + listar usuarios, cambiar roles, habilitar/inhabilitar cuentas | Cambiar SU PROPIO rol (no auto-promoción); borrar usuarios o fijar su fecha de baja desde el panel |

Reglas transversales:

- El registro público solo puede crear usuarios con rol `miembro`.
- Toda petición sin token válido a una ruta protegida responde `401`; autenticado sin el rol mínimo responde `403`.
- La autorización se aplica en la capa de aplicación; la base de datos no declara políticas de seguridad a nivel de fila.

## 4. Reglas de negocio

| # | Regla (nivel de producto) |
|---|---|
| **R1** | **Identidad única y soft-delete selectivo**: `usuarios` es la única tabla de identidad — perfil, credenciales y rol viven en ella; su `id` (UUID) es la identidad del usuario y coincide con el `sub` del JWT, resolviendo sin JOIN. Solo `usuarios` tiene soft-delete vía `fecha_eliminacion`: no existe `DELETE` físico en la baja; toda ruta de lectura filtra `fecha_eliminacion IS NULL` (cuenta dada de baja → sin sesión y sin aparecer en listados). La baja no destruye anuncios, conversaciones ni mensajes. Las demás tablas no tienen `fecha_eliminacion`: `anuncios` usa su propio estado de dominio `estado='eliminado'`. |
| **R2** | **Unicidad de email case-insensitive**: el email es único entre cuentas activas comparando `lower(email)` — `A@correo.com` y `a@correo.com` son la MISMA cuenta (registro y login normalizan a minúsculas). La unicidad se declara como índice único parcial `WHERE fecha_eliminacion IS NULL`, por lo que el email de una cuenta dada de baja queda libre para reutilización. Un email ya activo → `409 Conflict`. El email es de solo lectura después del registro. |
| **R3** | **Roles**: dominio cerrado `miembro` / `moderador` / `admin` (cualquier otro valor viola el CHECK de la BD), con jerarquía e herencia de permisos. El rol viaja como claim del JWT (`roles`), pero la autoridad de autorización es la fila del usuario en la BD, resuelta en cada petición autenticada: un cambio de rol surte efecto en la siguiente petición, sin esperar la expiración del token (máximo 15 minutos). El claim es una optimización de contexto, nunca la autoridad. |
| **R4** | **Visibilidad del catálogo**: el catálogo público solo expone anuncios con `estado='disponible' AND estado_revision='aprobado'`. Cualquier otro estado (`inactivo`, `vendido`, `eliminado`) o revisión (`pendiente`, `rechazado`) queda oculto esté o no autenticado el cliente. El detalle de un anuncio no visible o inexistente responde `404` indistinguible: la API pública no revela la existencia de anuncios no visibles. El predicado se aplica en la capa de aplicación, respaldado por un índice compuesto `(estado, estado_revision, fecha_creacion DESC)`. |
| **R5** | **Moderación sin auto-aprobación**: todo anuncio nace con `estado_revision='pendiente'`, independientemente del rol del creador — también los de un `admin` — y ninguna ruta ni parámetro permite crear con `aprobado`. Las columnas de moderación (`estado_revision`, `revisado_por`, `fecha_revision`, `motivo_rechazo`) solo las escribe el flujo de moderación; un vendedor que edita su anuncio no las altera. Nadie puede aprobar, rechazar ni retraer UN ANUNCIO PROPIO (403, incluso para `moderador` y `admin`). Todo rechazo o retracción exige `motivoRechazo` no vacío y escribe la auditoría completa (`revisado_por`, `fecha_revision`, y en rechazo/retracción `motivo_rechazo`); solo se conserva la última revisión. Un rechazo conserva además su **procedencia** (`origen_rechazo`): `rechazo` cuando lo decide la revisión y `retraccion` cuando se revoca una aprobación previa; el estado publicado sigue siendo `rechazado` para ambos, y el panel del vendedor los separa en chips distintos. |
| **R6** | **Edición resetea la revisión**: editar cualquier campo de contenido de un anuncio `aprobado` o `rechazado` lo devuelve a `estado_revision='pendiente'` (reingresa a la cola) y limpia el trail de la revisión anterior (`motivo_rechazo`, `revisado_por`, `fecha_revision`), de modo que el vendedor puede corregir y reintentar. Un anuncio `pendiente` editado permanece `pendiente`. |
| **R7** | **Ownership**: un vendedor solo actualiza, elimina, marca como vendido o alterna la visibilidad de SUS anuncios (`vendedor_id` = su `id`, resuelto desde el JWT). Toda operación sobre un anuncio ajeno responde `403`; la regla se aplica en el servicio Y como predicado de propiedad en el repositorio, de modo que "mis anuncios" nunca devuelve filas ajenas aunque se manipule el filtro. |
| **R8** | **Precios e imágenes**: `precio` numérico con rango `0 ≤ precio ≤ 99999999.99` (negativo o superior → rechazado). Hasta **5 imágenes** por anuncio, con los arrays `imagenes_urls` e `imagenes_paths` siempre de la MISMA longitud y pareados por posición (el CHECK solo impone la igualdad de longitudes; la correspondencia por posición la garantiza la aplicación, que rechaza un par mal construido). Subida exclusivamente multipart vía API (sin URLs prefirmadas), máximo 5 MB por imagen y tipos solo `jpeg/png/gif/webp`; el avatar de perfil admite 2 MB con los mismos tipos y una sola fuente de configuración de límites. La API expone solo URLs públicas (`imagenesUrls`): las claves de objeto del almacenamiento nunca salen en el wire. |
| **R9** | **Deduplicación de conversaciones**: existe exactamente una conversación por par `(anuncio_id, comprador_id)` — una UNIQUE lo impone a nivel de datos; reutilizar el par devuelve la existente (`200` en vez de crear otra). Prohibida la auto-conversación: `comprador_id <> vendedor_id` (CHECK en BD → `422`). `vendedor_id` de la conversación se deriva del anuncio en el servidor; un `vendedorId` enviado por el cliente se ignora. |
| **R10** | **Mensajes no vacíos y solo entre participantes**: el `texto` del mensaje se recorta y no puede quedar vacío ni de solo espacios (aplicación → `422`; CHECK `btrim(texto) <> ''` en BD). Solo los dos participantes (`comprador_id` o `vendedor_id`) leen, envían o marcan como leídos; un no participante recibe `403`. |
| **R11** | **Tokens hasheados, con expiración y un solo uso**: los tokens de confirmación de email (ventana **24 horas**) y de restablecimiento de contraseña (ventana **60 minutos**) se persisten SIEMPRE como hash SHA-256, nunca en plaintext; `fecha_expiracion` es obligatoria; el consumo es transaccional y de un solo uso (`fecha_uso`); la búsqueda es por hash del valor presentado. A lo sumo UN token activo por usuario y propósito: reenviar invalida los anteriores. El token crudo solo viaja una vez, por email o en la respuesta HTTP. Las contraseñas se guardan con hash BCrypt (mínimo 8 caracteres, sin reglas de composición, política uniforme en registro, cambio y reset) y `hash_contrasena` nunca aparece en ninguna respuesta. |
| **R12** | **Refresh rotativo con detección de reuso**: el login emite un access token JWT de **15 minutos** (`sub` = `usuarios.id`, claim `roles`) y un refresh token opaco de **30 días** almacenado solo como hash. Cada refresh ROTA: el anterior se marca usado y el nuevo se inserta en la MISMA transacción, compartiendo `familia_id`. Presentar un refresh ya usado (posible robo) responde `401` y revoca TODA la familia. Token expirado, revocado o desconocido → `401`. El logout revoca el refresh server-side de forma idempotente (siempre `204`) sin invalidar el access token, que expira por sí solo dentro de su TTL. La sesión del cliente solo persiste el refresh token (localStorage / secure store); el access token vive en memoria. |
| **R13** | **No-enumeración en el reset**: el request de restablecimiento responde con EXACTAMENTE el mismo status y cuerpo exista o no la cuenta; el email solo se envía si la cuenta existe y está activa; el endpoint aplica límite de tasa (`429`). Las respuestas de login no revelan si una cuenta existe, está inhabilitada o dada de baja (mismo `401` genérico que credenciales inválidas). |
| **R14** | **Solo admin cambia roles, sin auto-promoción**: el cambio de rol es exclusivo de `admin`. Un `admin` no puede cambiar SU PROPIO rol (`403`); `moderador`/`miembro` reciben `403`. El rol destino debe pertenecer al dominio (`miembro`/`moderador`/`admin`) o responde `422`; cambiar a un rol igual al actual también responde `422` sin efectos. El cambio se refleja en los próximos tokens emitidos. |
| **R15** | **Cuentas inhabilitadas o dadas de baja sin sesión**: `habilitado=false` (decisión reversible del `admin`) o `fecha_eliminacion IS NOT NULL` impiden login, refresh y toda lectura de perfil, con respuesta indistinguible de credenciales inválidas. El panel admin NUNCA borra usuarios físicamente ni escribe `fecha_eliminacion` — la baja pertenece solo al flujo del propio usuario. Re-habilitar restaura el login. |
| **R16** | **Mensajería y visibilidad del anuncio**: abrir una conversación exige que el anuncio esté visible (`estado='disponible' AND estado_revision='aprobado'`) y que su vendedor esté activo (`habilitado=true`, `fecha_eliminacion IS NULL`). Los mensajes a una conversación existente se envían mientras ambos participantes estén activos, sin importar el estado posterior del anuncio. El historial existente permanece legible para los dos participantes en todo caso. |
| **R17** | **Nombre público del vendedor**: toda vista de un anuncio —catálogo, detalle y panel— publica `vendedorNombre` junto a `vendedorId`: **nombre de pila + inicial del último apellido** (`"Ana Pérez"` → `"Ana P."`), máximo 60 caracteres. El nombre completo nunca viaja al wire. El listado resuelve los nombres en lote (una consulta por página, nunca una por anuncio) y el detalle carga uno. Cuando el nombre no es resoluble —cuenta inexistente, dada de baja o sin nombre— se publica el genérico `"Vendedor"`, que no revela el estado de la cuenta (R1, R15). |

## 5. Requisitos funcionales de alto nivel

| Capacidad | Qué incluye |
|---|---|
| **Datos** | 5 tablas de negocio (`usuarios`, `categorias`, `anuncios`, `conversaciones`, `mensajes`) + 3 tablas de tokens (`tokens_verificacion_email`, `tokens_restablecimiento_contrasena`, `tokens_refresh`); CHECKs, UNIQUEs, FKs con comportamiento `ON DELETE` y 16 índices por patrón de query (13 de negocio + 3 de tokens activos); migraciones versionadas forward-only e inmutables; seed idempotente de 6 categorías base. |
| **Identidad** | Registro con confirmación de email obligatoria (bloquea el login hasta confirmar), reenvío de confirmación con cooldown (N5), login con emisión de JWT, refresh con rotación, logout, restablecimiento y cambio de contraseña, lectura/actualización de perfil, subida/eliminación de avatar y baja de cuenta soft-delete. |
| **Autorización** | Jerarquía de roles, matriz de permisos por operación con caso permitido y denegado por regla, ownership de anuncios, protección de columnas de moderación, prohibiciones de auto-aprobación y auto-promoción. |
| **Catálogo público** | `GET /v1/anuncios` y detalle: solo anuncios visibles, paginación por cursor opaco estable (default 20, máximo 100), orden por defecto `fechaCreacion DESC` (también `precio`), filtro `categoriaId`, búsqueda `q` en nombre y descripción con escape de comodines `%`, `_` y `\`. Cada anuncio publica el nombre público de su vendedor (`vendedorNombre`, R17), resuelto en lote para la página. |
| **Anuncios (vendedor)** | Crear siempre `pendiente` (201 + `Location`), editar con reset a `pendiente`, toggle de visibilidad solo sobre `aprobado` (`disponible ↔ inactivo`), marcar vendido, baja por estado `eliminado` sin destruir conversaciones ni objetos, panel "mis anuncios" en offset con badge de revisión y `motivoRechazo`, lectura de un anuncio propio por id (aunque no sea visible públicamente) y filtro por estado de revisión con `total` filtrado. |
| **Almacenamiento de medios** | Clave de objeto única `anuncios/{vendedorId}/{uuid}.{ext}`, subida multipart exclusiva vía API, lectura pública por URL, borrado sin objetos huérfanos al reemplazar o eliminar, escritura atómica de ambos arrays de imágenes en la misma transacción que la fila. |
| **Mensajería** | Crear/reusar conversación por anuncio (idempotente con `Idempotency-Key`), enviar mensajes de texto, historial con cursor hacia atrás (página inicial = más recientes), conteo y marcado de no leídos como acción explícita (un `GET` no muta `leido`), lista "mis conversaciones" ordenada por actividad con `ultimoMensaje` y `noLeidos`. Apertura de conversación solo sobre anuncios visibles con vendedor activo (R16). Sin tiempo real: la respuesta HTTP del envío es el contrato. |
| **Notificaciones** | Emails transaccionales de confirmación y reset, envío asíncrono post-commit con backoff y máximo de intentos (un fallo del proveedor nunca revierte el flujo HTTP), reenvío con cooldown configurable, un token activo por propósito, log de entrega (logs estructurados de aplicación con `traceId`, sin tabla de auditoría), configuración externalizada. |
| **Administración** | Cola de moderación solo `pendiente` en orden FIFO (`fechaCreacion ASC`), aprobación/rechazo/retracción como acciones explícitas con motivo obligatorio, listado admin por `estadoRevision`, CRUD de categorías con `orden = MAX(orden)+1` y borrado físico (`ON DELETE SET NULL`: los anuncios sobreviven sin categoría), gestión de usuarios (listado filtrable por rol, cambio de rol, habilitar/inhabilitar). |
| **Contrato de API** | OpenAPI como fuente única de verdad bajo `/v1`; cliente HTTP generado consumido en exclusiva por web y móvil (generan su cliente desde este OpenAPI en sus repos propios; la validación del contrato se hace en el CI de este repo); errores RFC 9457 (`application/problem+json`) sin stack traces; split único 400 (problema estructural) / 422 (valor que viola una regla); naming en plural kebab-case, wire `camelCase` vs columnas `snake_case`; idempotencia con `Idempotency-Key`. |
| **Clientes web y móvil (repos propios)** | Existen como producto pero sus repositorios, frameworks y tooling quedan fuera de este repositorio: aquí no hay JS/TS ni build frontend. Consumen exclusivamente el cliente HTTP generado desde el OpenAPI (prohibidos clientes escritos a mano) y trasladan a sus repos las obligaciones de producto: guardas de sesión y rol, refresh transparente ante `401`, validación en espejo al contrato, render seguro sin HTML inyectado, WCAG 2.2 nivel AA (contraste, foco visible, objetivo táctil mínimo, estados no basados solo en color, `prefers-reduced-motion`), temas claro/oscuro e interfaz en español. |
| **Repositorio backend exclusivo** | Repo único para la API: Java 21, Maven, Spring Boot 4, Spring MVC, PostgreSQL 18; sin pnpm, sin JS/TS, sin monorepo ni tooling frontend; migraciones de BD solo aquí; CI por Maven; idioma de comentarios en español. |

## 6. Requisitos no funcionales

| Categoría | Requisito |
|---|---|
| **Seguridad** | Contraseñas BCrypt con política NIST 800-63B (mínimo 8, sin composición); tokens de sesión siempre hasheados; `hash_contrasena` jamás en respuestas; autorización por rol en la capa de aplicación (sin políticas a nivel de fila); `401` sin sesión vs `403` sin permiso; respuestas de error sin fugas internas; rate limiting y no-enumeración en el reset. |
| **Rendimiento / indexación** | 16 índices justificados por patrón de query: compuesto de visibilidad del catálogo (con desempate por `id`), GIN trigram para búsqueda `ILIKE` en nombre y descripción, índices de no leídos y de participantes de conversaciones, índice parcial de email activo, índice de rol para el panel admin e índices parciales de tokens activos por usuario. Los índices de participantes de conversaciones son compuestos con `fecha_actualizacion` para "mis conversaciones". `fecha_actualizacion` lo mantiene la aplicación en cada UPDATE (sin triggers de BD). |
| **Consistencia de contrato** | Cambios incompatibles en el contrato rompen el build del cliente generado en CI; envelope de colección consistente (`datos` + `paginacion`) con cursor opaco por catálogo/historial y offset con conteo total en paneles; parámetros fuera de dominio o rango → `400`. |
| **Pruebas** | Suite Maven unificada (`mvn test`); backend probado contra PostgreSQL real (nada de sustitutos en memoria para integración); gate de cobertura: núcleo/dominio ≥ 80 % (los gates de UI pertenecen a los repos de clientes); cada guard de autorización con al menos un caso permitido y uno denegado; ciclo de moderación end-to-end; tests negativos de guardrails. |
| **Accesibilidad** | WCAG 2.2 AA verificable con clase de verificación declarada por requisito (lint estático, test de contraste, inspección de foco) en web y móvil. |
| **Entrega y evolución** | Migraciones forward-only: una migración aplicada es inmutable; correcciones siempre como migración nueva. Autorización sin dependencia de la BD para que las reglas de negocio queden versionadas en código y testeables. |
| **Operación / respaldo** | Backup diario automatizado de PostgreSQL con retención de 7 días (Q4, 2026-10-10). Sin SLO de rendimiento inventados: se fijan en pre-producción con latencias reales (Q2). Sin SLA de moderación (Q3). |

## 7. Fuera de alcance

- Pasarela de pago o checkout: el precio es informativo y el trato se cierra por mensajería.
- Chat en tiempo real: sin WebSocket/STOMP, sin push y sin eventos de mensaje; el refresco es on-demand o por polling del cliente.
- Notificaciones push y emails distintos de confirmación de registro y restablecimiento de contraseña.
- Restricción de dominio de correo institucional: se acepta cualquier email sintácticamente válido.
- Edición o borrado de mensajes: el historial es inmutable por diseño.
- Borrado de conversaciones: las conversaciones persisten (retención); un borrado físico destruiría el hilo de ambos participantes.
- URLs prefirmadas de subida al storage: la única puerta de subida es la API multipart.
- Borrado de objetos de imagen al dar de baja un anuncio: la baja conserva los objetos; el borrado ocurre solo al reemplazar/eliminar imágenes en edición.
- Historial de decisiones de moderación: solo se conserva la última revisión, sin tabla de historial ni métricas por moderador (tradeoff aceptado).
- Gestión institucional (aulas, cursos, acreditación): fuera del dominio comercial.
- Búsqueda insensible a acentos: `electronica` no encuentra `Electrónica` (decisión 2026-10-12; el índice trigram se mantiene efectivo sin normalización).
- Repositorios de los clientes web y móvil (implementación, frameworks, generación de su cliente): cada app en su repositorio propio; este repositorio es exclusivamente backend.- SLA o garantía de tiempo de moderación: la cola FIFO se procesa según disponibilidad del equipo de moderación; el resultado es visible para el vendedor en "mis anuncios" (badge + motivoRechazo).

## 8. Criterios de éxito / aceptación

Checklist verificable:

> **Verificación (2026-10-26)**: 18 de los 19 criterios están evidenciados por la suite automatizada — detalle por criterio en `odd/tasks/aceptacion-final.md`. El criterio sobre los clientes web/móvil queda fuera de este repositorio por diseño (repos propios que consumen el contrato).

- [ ] Un registro nuevo crea usuario `miembro` sin email confirmado y el login se rechaza hasta confirmar.
- [ ] Registrar dos veces el mismo email en distinto caso responde `409` y no crea duplicados.
- [ ] El email de una cuenta dada de baja queda disponible y la baja no destruye anuncios, conversaciones ni mensajes.
- [ ] Todo anuncio creado —incluido el de un `admin`— queda `pendiente` y no aparece en el catálogo hasta la aprobación.
- [ ] El catálogo solo devuelve `estado='disponible' AND estado_revision='aprobado'`, y el detalle de un no visible responde `404` indistinguible de "no existe".
- [ ] Ningún rol puede aprobar, rechazar ni retraer su propio anuncio, ni cambiar su propio rol (tests con caso permitido y denegado).
- [ ] Todo rechazo o retracción deja `revisado_por`, `fecha_revision` y `motivoRechazo` poblados; sin motivo → `422`.
- [ ] Editar un anuncio `aprobado` o `rechazado` lo devuelve a `pendiente` y limpia el trail de la revisión anterior.
- [ ] Solo existe una conversación por par comprador–anuncio, no se permite la auto-conversación y un mensaje vacío nunca se persiste.
- [ ] No se puede abrir una conversación sobre un anuncio no visible ni escribir a una cuenta inhabilitada o dada de baja; el historial previo permanece legible para los participantes.
- [ ] Ninguna tabla de tokens contiene plaintext; un token consumido o expirado no se reutiliza; el reuso de un refresh revoca toda la familia.
- [ ] El request de reset responde idéntico con email existente o inexistente y aplica límite de tasa.
- [ ] Un vendedor no puede modificar anuncios ajenos (403) ni ver anuncios ajenos en su panel.
- [ ] Los límites de imagen (5 imágenes pareadas, 5 MB, avatar 2 MB, tipos permitidos) se rechazan con el status correcto sin subir objetos.
- [ ] Suite de pruebas en verde con el gate de cobertura (dominio ≥ 80 %; los de UI pertenecen a los repos de clientes) y el contrato validado en CI.
- [ ] Web y móvil consumen exclusivamente el cliente generado; ninguna app define su propio acceso a la API.
- [ ] Sin políticas de seguridad a nivel de fila ni triggers de infraestructura de identidad/auditoría en la base de datos.
- [ ] Cada anuncio publica el nombre público de su vendedor (nombre de pila + inicial del último apellido, máximo 60) y nunca el nombre completo; sin nombre resoluble responde `"Vendedor"` (R17).
- [ ] Un anuncio retraído se distingue de uno rechazado en el panel y en el filtro, sin que `estadoRevision` gane valores: la procedencia es un dato aparte (R5).

## 9. Preguntas abiertas

Vacíos detectados al redactar este PRD; el estado de resolución se indica en cada fila:

| # | Pregunta |
|---|---|
| Q1 | **Límite de tasa en login y registro** — **RESUELTA (2026-10-10)**: sí, se aplica rate limiting también al inicio de sesión y al registro (por IP + email, con límites externalizados); el reset mantiene su `429` explícito. Decisión adoptada en el SDD §8 como configuración por defecto. |
| Q2 | **Objetivos de rendimiento** — **RESUELTA (2026-10-10)**: sin SLO inventados; los 16 índices cubren cada patrón de query y los objetivos se fijarán en pre-producción con latencias reales medidas. |
| Q3 | **SLA de moderación** — **RESUELTA (2026-10-10)**: sin SLA comprometido; la cola FIFO se procesa según disponibilidad del equipo de moderación y el vendedor ve la decisión en "mis anuncios" (badge + motivoRechazo). Sin aviso adicional (ver fuera de alcance). |
| Q4 | **Retención y backup** — **RESUELTA (2026-10-10)**: mínimo exigible = backup diario automatizado de PostgreSQL con retención de 7 días; la retención tras la baja de cuenta ya está definida en R1 (los datos se conservan intactos). |
| Q5 | **Dueño nominal de producto** — **RESUELTA (2026-10-10)**: el propietario del proyecto asume la autoridad de decisión de producto y priorización de alcance (ver cabecera). |
