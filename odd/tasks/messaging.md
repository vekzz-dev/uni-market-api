# Feature: messaging

| Campo | Valor |
|---|---|
| Estado | Completada (2026-10-12; 160 tests, cobertura 90.6%) |
| Creado | 2026-10-12 |
| Alcance | Módulo `messaging` (SDD §4): conversaciones con dedupe (R9), mensajes (R10), apertura y envío con actividad (R16), no leídos explícitos. Cierra el flujo comprador–vendedor completo |
| No incluye | Tiempo real (PRD §2), notificaciones de mensaje (fuera de alcance), borrado/edición de mensajes (inmutables) |
| Presupuesto | ~1300 líneas con tests; single-pr |
| Documentos | `docs/prd.md` (R9/R10/R16, §5 Mensajería), `docs/database-design.md` (§3 conversaciones/mensajes, §5 reglas), `docs/system-design.md` (§5.4, D4), `openapi.yaml` (paths `/v1/conversaciones*`, `/v1/mis-conversaciones`) |

## Decisiones de diseño

- **Acceso a catalog por su servicio expuesto** (SDD dependencies): `abrir` usa `ServicioCatalogo.detalle` (visibilidad R4 ⇒ 404 indistinguible) y se añade `resumenSinVisibilidad(id)` para el historial (una conversación existe aunque el anuncio después sea `vendido|inactivo|eliminado` — R16: historial siempre legible).
- **R16 en apertura**: anuncio visible + vendedor **activo**; vendedor inexistente/inhabilitado/dado de baja → `404` indistinguible (se consulta vía `EstadoUsuarioProvider` de `shared`). Auto-conversación → `422` (CHECK + servicio).
- **R16 en envío**: el remitente activo lo garantiza el filtro de autoridad (D3); el **receptor** se valida aquí → inactivo → `422`. El historial no se bloquea por eso.
- **Dedupe (R9)**: UNIQUE `(anuncio_id, comprador_id)`; reuso → `200` con la existente; `Idempotency-Key` opcional (eco; el dedupe natural es el mecanismo, D6). `vendedorId` siempre derivado del anuncio.
- **Historial (D4)**: cursor compuesto `(fechaCreacion, id)` hacia atrás; página inicial = más recientes. `GET` jamás muta `leido`; el marcado es acción explícita (`POST /lecturas`).
- **fechaActualizacion de la conversación** se actualiza en cada envío (ordena "mis conversaciones").
- `ultimoMensaje` y `noLeidos` por ítem del panel: consultas acotadas por página (≤100) — N+1 aceptado consciente a escala campus (documentado).
- Stub de slices: se añade `ServicioConversaciones` a la fábrica (convención).

## Tareas

- [x] **MS1 — Modelo**: `Conversacion` (+`esParticipante`/`contraparteDe`) y `Mensaje` inmutable + repositorios (par único, top de último mensaje, conteo y marcado explícito de leídos) + specs (participante, cursor hacia atrás). Ruta: inline.
- [x] **MS2 — Catalog expone resumen**: `ServicioCatalogo.resumenSinVisibilidad(id)` — historial legible aunque el anuncio sea vendido/inactivo/eliminado (R16); documentado como consumo interno entre módulos, jamás ruta pública. Ruta: inline.
- [x] **MS3 — Servicio y controller**: `ServicioConversaciones` (apertura con dedupe 201/200 + R16 + 404 indistinguible + 422 auto; panel con `ultimoMensaje`/`noLeidos`; detalle; historial con cursor; envío con contraparte activa; lecturas idempotentes) + `ControladorConversaciones` + `DtosMensajeria`. Ruta: inline.
- [x] **MS4 — Tests**: repo 5 (UNIQUE par, CHECK auto, participantes, no leídos/marcado, cursor con empates), unit 8 (R9/R10/R16 con dobles), E2E 4: **ciclo real con aprobación por la API de moderación**, historial legible tras la baja del anuncio, cursor hacia atrás con límite, guards completos 403/404/422/401. Stub de slices añadido (convención). Ruta: inline.
- [x] **MS5 — Verificación y commit**: `mvn clean verify` EXIT=0 (160 tests, 90.6 % líneas, gate activo). Ruta: padre.

## Evidencia (2026-10-12)

- Fixture inicial violó el CHECK `chk_revision_trail_consistente` (anuncio aprobado sin trail): corregido el INSERT del test — tercera vez que las constraints reales corrigen un fixture mío; siguen haciendo su trabajo.
- E2E: apertura 201 → repetición 200 con el mismo id, texto recortado, `GET` sin mutar `leido`, marcado explícito, no leídos por rol, contraparte inactiva 422, vendedor inactivo en apertura 404, auto-conversación 422.

## Criterios de aceptación

- Una sola conversación por par (R9) con reuso `200`; auto-conversación `422`; apertura sobre anuncio no visible o vendedor inactivo `404` indistinguible.
- Historial legible tras vender/desactivar/eliminar el anuncio (R16) y cursor estable con timestamps iguales.
- Ningún `GET` muta `leido`; marcado explícito idempotente.
- Envío: texto recortado no vacío (422), no participante (403), receptor inactivo (422).
- `mvn clean verify` EXIT=0 con cobertura ≥ 80 %; ArchUnit, contrato y suites previas verdes.
