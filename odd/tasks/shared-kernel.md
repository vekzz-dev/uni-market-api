# Feature: shared-kernel

| Campo | Valor |
|---|---|
| Estado | Completada (2026-10-10) |
| Creado | 2026-10-10 |
| Alcance | Módulo `shared` del SDD §4 — sin lógica de negocio: errores, paginación/cursor y piezas del kernel de seguridad |
| Presupuesto (heurística ~400 líneas) | ~800 líneas con tests — excede la heurística a propósito: es el cimiento transversal y su cobertura de pruebas es requisito (PRD §6); sin él los 7 módulos restantes duplicarían estos mecanismos |
| Estrategia de entrega | single-pr |
| Documentos | `docs/system-design.md` §4 (shared), §6 (envelope/cursor/errores), §8 (seguridad), D4 (cursor), D3/R3 (autoridad BD), `openapi.yaml` (schemas `Problema`, `Paginacion*`) |

## Objetivo

Entregar el kernel transversal del backend: manejo de errores RFC 9457 con el split 400/422, envelope de colecciones con cursor compuesto `(fechaCreacion, id)`, y las piezas de seguridad que no dependen de identidad (decoder JWT, jerarquía de roles, puerto de autoridad + filtro condicional).

## Decisiones de diseño de esta feature

- `Rol` (enum del dominio cerrado, R3) vive en `shared/security`: es parte del contrato de autorización (claim del JWT) y los 8 módulos lo consumen; `shared` no puede depender de `identity` (regla ArchUnit).
- El **filtro de autoridad por request** (D3: la BD es autoridad) se entrega como pieza condicional: `@ConditionalOnBean(EstadoUsuarioProvider.class)` — `identity` aportará la implementación; hasta entonces no altera el arranque.
- Bean Validation → **400** (estructural); reglas de negocio → **422** vía excepción de dominio (PRD §6). El texto en blanco (R10) y motivo vacío (R5) son reglas de servicio, no de DTO.
- `ProblemDetail` nativo de Spring; sin stack traces; `instance` = URI del request.

## Tareas

- [x] **T1 — Errores**: excepciones (`ApiException` + `EstructuraInvalidaException` 400, `ReglaDeNegocioException` 422, `NoEncontradoException` 404, `ConflictoException` 409, `ProhibidoException` 403) + `ApiExceptionHandler` (`@RestControllerAdvice`) con `application/problem+json` + mapa controlado (Bean Validation/JSON malformado/tipo → 400; acceso denegado → 403; fallback 500 sin fuga). Ruta: delegado. **Evidencia**: `ApiExceptionHandlerTest` 10/10 verde. **Corrección del padre**: el fallback `Exception` se tragaba `NoResourceFoundException` (ruta no mapeada → 500); añadido handler → 404 problem+json con test propio.
- [x] **T2 — Paginación**: records `PaginacionCursor`, `PaginacionOffset`, `RespuestaColeccion<T>` + `CursorCodec` (encode/decode base64url de `(fechaCreacion, id)`; malformado → 400). Ruta: delegado. **Evidencia**: `CursorCodecTest` 9/9 verde (roundtrip preserva micros, malformados → 400).
- [x] **T3 — Seguridad**: `Rol`, `EstadoUsuario`, puerto `EstadoUsuarioProvider`, `PropiedadesJwt`, `JwtDecoder` Nimbus HS256, `RoleHierarchy`, `FiltroAutoridadUsuario`, `AuthenticationEntryPoint`/`AccessDeniedHandler` Problem JSON. Ruta: delegado. **Evidencia**: `FiltroAutoridadUsuarioTest` 6/6 verde (activo → `ROLE_*`; inexistente/inhabilitado/baja → 401 genérico). **Desviación aceptada**: decoder condicionado con `Condition` propia en vez de `@ConditionalOnProperty`, porque en Boot 4.1 la anotación hace match con secreto vacío (`""`) y `SecretKeySpec` vacío revienta el arranque — verificado con bytecode y prueba empírica del writer.
- [x] **T4 — Config**: `app.seguridad.jwt.secreto` (solo entorno, sin default) + `ttl-acceso: 15m`. Ruta: delegado.
- [x] **T5 — Tests**: los 3 suites verde (25 tests nuevos). Ruta: delegado.
- [x] **T6 — Verificación y commit**: spot check del padre `mvn -q -B verify` EXIT=0; commits `feat(shared)`. Ruta: padre.

## Criterios de aceptación

- `mvn -q -B verify` verde; ArchUnit sigue verde (ahora con clases reales en `shared`).
- Los 3 tests nuevos pasan y cubren los caminos felices y de error listados.
- `shared` no importa ningún otro módulo (regla ArchUnit existente lo garantiza).
- Cero dependencias nuevas en el pom (todo lo necesario ya está: security, oauth2-resource-server, webmvc-test, assertj).

## Verificación

- `mvn -q -B verify` → exit 0.
- `mvn -q -B test -Dtest='CursorCodecTest,ApiExceptionHandlerTest,FiltroAutoridadUsuarioTest'` → tests verdes.
