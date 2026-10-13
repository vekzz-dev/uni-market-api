# Feature: moderation

| Campo | Valor |
|---|---|
| Estado | Completada (2026-10-12; 143 tests, cobertura 89.3%) |
| Creado | 2026-10-12 |
| Alcance | Módulo `moderation` (SDD §4): cola FIFO por estado de revisión + decisiones aprobar/rechazar/retraer con motivo (R5). Cierra el ciclo end-to-end del catálogo (publicar → decidir → visible). |
| No incluye | CRUD de categorías y gestión de usuarios (módulo `admin`), historial de decisiones (fuera de alcance por PRD §7: solo última revisión). |
| Presupuesto | ~700 líneas con tests; single-pr |
| Documentos | `docs/prd.md` (R4, R5), `docs/database-design.md` (§3 anuncios, §5 trail), `docs/system-design.md` (§5.3, §4 dependencias), `openapi.yaml` (paths `/v1/moderacion/*`) |

## Decisiones de diseño

- **El servicio de revisión vive en `catalog`** (`ServicioRevision`): es el escritor del agregado `Anuncio` y de sus invariantes (R5/R6); `moderation` lo consume como servicio de aplicación expuesto — exactamente la dependencia que declara el SDD §4 ("moderation opera sobre anuncios de catalog: servicio de aplicación de catalog expone operaciones de revisión"). Nada de importar repositorios ajenos (regla ArchUnit).
- **Máquina de estados**: `aprobar` y `rechazar` exigen `pendiente`; `retraer` exige `aprobado`; cualquier otro origen → `422`. `rechazar`/`retraer` exigen motivo no vacío → `422` (R5).
- **Anti-auto-decisión (R5)**: `vendedorId == revisor` → `403` ANTES de validar el estado, incluso `admin`. Nota: la cola no filtra anuncios propios (contrato); el bloqueo ocurre en la decisión.
- **Trail**: decidir escribe `revisadoPor` + `fechaRevision`; `rechazar`/`retraer` además `motivoRechazo`; `aprobar` limpia `motivoRechazo`. Solo se conserva la última revisión (PRD §7).
- **Cola**: filtro por `estadoRevision` (default `pendiente`), orden FIFO `fechaCreacion ASC, id ASC` (desempate estable para offset), offset + total con `AnuncioPanel`.
- **Enum inválido en body** → `400` estructural (deserialización); motivo vacío → `422` (regla).
- Rol: `@PreAuthorize("hasAnyRole('MODERADOR','ADMIN')")` — jerarquía ya configurada en `shared`.
- Convención de slices: `StubsParaSlicesContextCustomizerFactory` se extiende con el stub del nuevo servicio (los `@WebMvcTest` genéricos escanean todos los controladores).

## Tareas

- [x] **M1 — Servicio de revisión (catalog)**: `ServicioRevision.decidir` (404/403/422 + trail completo + limpieza de motivo al aprobar) y `cola` (filtro + FIFO estable + offset). Ruta: **inline** (tercer rechazo de permisos al delegar; decisión previa del propietario de seguir inline).
- [x] **M2 — Moderación (controller + DTO)**: `ControladorModeracion` (GET cola + POST decisiones, `@PreAuthorize` moderador/admin) + `DtosModeracion.DecisionRequest`. El enum `Decision` vive en `catalog` para evitar el ciclo moderation→catalog→moderation. Ruta: inline.
- [x] **M3 — Tests**: unit 8/8 (máquina de estados completa, motivo, anti-auto, 404, cola) + integración 3/3 E2E: **el ciclo publicar→aprobar→visible ya funciona por API real** (sin el truco de aprobar por repositorio de features anteriores), retraer deja motivo visible en `mis-anuncios` del vendedor, guards 403/404/422/400 y jerarquía admin. Stub de `ServicioRevision` añadido a la fábrica de slices (convención). Ruta: inline.
- [x] **M4 — Verificación y commit**: `mvn clean verify` EXIT=0 (143 tests, 0 fallos, 89.3 % líneas, gate activo); commits `feat(moderation)`. Ruta: padre.

## Evidencia (2026-10-12)

- `mvn -q -B test -Dtest='ServicioRevisionTest,ModeracionFlujoIntegrationTest'` → EXIT=0.
- `mvn -q -B clean verify` → EXIT=0; 143 tests; cobertura 89.3 %.
- Nota de contrato: la cola no filtra anuncios propios (así lo define el contrato); el bloqueo anti-auto ocurre al decidir (403), con test explícito.

## Criterios de aceptación

- El ciclo publicar→decidir→visible funciona por API sin manipular la BD (el truco del repo para aprobar desaparece del flujo real).
- Cada guard con caso permitido y denegado: moderador decide ajeno (200) vs propio (403); admin decide (200); miembro no decide (403); estados inválidos (422) y falta de motivo (422).
- Trail completo tras rechazo/retracción (test explícito); `aprobar` limpia motivo previo.
- `mvn clean verify` EXIT=0 con cobertura ≥ 80 %; ArchUnit y contrato verdes.
