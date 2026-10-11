# Feature: identity

| Campo | Valor |
|---|---|
| Estado | Completada (2026-10-11) |
| Creado | 2026-10-11 |
| Alcance | Módulo `identity` (SDD §4) + puertos mínimos de `notifications` y `media` necesarios para registro/confirmación/reset/avatar — sin catálogo, moderación, mensajería ni admin |
| Presupuesto (heurística ~400 líneas) | ~1200 líneas con tests — excede la heurística a propósito: es el módulo más grande y del que todo depende; se entrega en un solo PR de feature con commits por unidad de trabajo |
| Estrategia de entrega | single-pr |
| Documentos | `docs/prd.md` (R1–R3, R11–R15, R2, Q1), `docs/database-design.md` (§3 usuarios + tokens, §5 reglas), `docs/system-design.md` (§4 identity, §5.1–§5.2 flujos, §8 seguridad, D2/D3/D12/D14), `openapi.yaml` (paths auth + usuarios) |

## Objetivo

Entregar autenticación y cuenta completas: registro → confirmación → login → refresh rotativo con detección de reuso → reset no-enumerable → perfil/avatar/baja, con la BD como autoridad del rol (D3) y tokens siempre hasheados (R11/R12).

## Decisiones de diseño de esta feature

- `Usuario` JPA (tabla `usuarios`) + 3 entidades de tokens (tablas `tokens_*`) con `AttributeConverter` para `Rol`; `fechaActualizacion` por `@PreUpdate` (sin triggers, DBDD §1).
- BCrypt para `hashContrasena`; SHA-256 para `hashToken` de los 3 tipos (R11) + refresh opaco (R12).
- Access JWT HS256 15 min (`sub` = id, claim `roles`); refresh opaco 30 días con `familiaId`, rotación transaccional y revocación de familia ante reuso (R12, SDD §5.2).
- Email: puerto `CorreoTransaccional` en `notifications` + adaptador log-a-consola en dev (D12, imprime token crudo) + adaptador Resend vía HTTP en prod (tras `app.notificaciones.proveedor=resend`); envío post-commit asíncrono con backoff simple, jamás revierte el HTTP.
- Avatar: puerto `AlmacenObjetos` en `media` + adaptador filesystem dev (raíz `${MEDIA_DIR:~/.uni-market}`, D14); R2 queda como adaptador prod pendiente (solo config + TODO registrado, no bloquea).
- `EstadoUsuarioProvider` (puerto de `shared`) implementado desde `usuarios` (resuelve rol/habilitado/baja por id) → activa el `FiltroAutoridadUsuario` condicional.
- Rate limiting (Q1/R13): filtro en memoria por IP+email para login/registro/reset-solicitud (ventana simple, 429 problem+json). Sin librería externa.
- Validación: Bean Validation en DTOs (estructural → 400); reglas de negocio en servicio → 422/409/401 según contrato.

## Tareas

- [x] **T1 — Persistencia**: entidades `Usuario`, `TokenVerificacionEmail`, `TokenRestablecimiento`, `TokenRefresh` + repositorios + `AttributeConverter` de `Rol`. Ruta: delegado.
- [x] **T2 — Registro y confirmación**: `POST /usuarios` (normaliza email a minúsculas, BCrypt, rol miembro, token 24h hasheado, 409 si email activo) + `GET /auth/confirmar-email` (transaccional un solo uso, fija `fechaVerificacionEmail`) + reenvío invalida previos (UNIQUE parcial). Ruta: delegado.
- [x] **T3 — Sesión**: `POST /auth/login` (401 genérico indistinguible R13/R15; emite JWT+refresh), `POST /auth/refresh` (rotación + revocación de familia ante reuso → 401), `POST /auth/logout` (204 idempotente). Ruta: delegado.
- [x] **T4 — Reset y contraseña**: `POST /auth/restablecer-contrasena` (202 idéntico exista o no, R13; token 60min; 429), `POST .../confirmar` (un solo uso), `POST /usuarios/me/contrasena` (verifica actual, política 8+). Ruta: delegado.
- [x] **T5 — Perfil/avatar/baja**: `GET/PATCH /usuarios/me` (email solo lectura), `PUT/DELETE /usuarios/me/avatar` (2MB, tipos permitidos, vía `AlmacenObjetos`), `DELETE /usuarios/me` (soft-delete, corta sesión). + `EstadoUsuarioProvider` + `SecurityChain` (públicas: registro, login, refresh, confirmar, reset; resto autenticado; jerarquía ya en shared). Ruta: delegado.
- [x] **T6 — Tests**: `@DataJpaTest` (UNIQUE email case-insensitive, trail, rotación), `@WebMvcTest`/`@SpringBootTest`+Testcontainers (flujos RED→GREEN: 409 duplicado, login bloqueado sin confirmar, reuso revoca familia, reset no-enumerable, 401 indistinguible, R16 no aplica aquí). Ruta: delegado.
- [x] **T7 — Verificación y commit**: padre spot check `mvn verify` + commits `feat(identity):`. Ruta: padre.

## Criterios de aceptación

- `mvn -q -B verify` verde; ArchUnit y contrato siguen verdes.
- Los 17 criterios PRD §8 tocados por identity (registro/login/confirmación/409/reuso/reset/baja) verificados por tests.
- `shared` no importa `identity` (ArchUnit); `FiltroAutoridadUsuario` activo con provider real.
- Cero secretos en código; JWT_SECRETO solo por entorno; tokens crudos solo en logs dev o email.

## Verificación

- `mvn -q -B verify` → exit 0.
- `mvn -q -B test -Dtest='*Identity*,*Auth*,*Usuario*'` → verdes.

## Evidencia de cierre (2026-10-11, spot check padre)

- `mvn -B verify` → BUILD SUCCESS, Tests run: 87, Failures: 0, Errors: 0 (incluye ArchUnit + contrato OpenAPI + 20 DbSchema + 29 shared/kernel previos).
- Delegado reportó RED→GREEN por slice (T1 compilación→13/13, T2–T5 IT 18/18 + slice 4/4).
