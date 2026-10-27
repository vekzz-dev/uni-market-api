# Feature: panel-filtros-retraccion

| Campo | Valor |
|---|---|
| Estado | Completada (2026-10-26; `mvn -B verify` EXIT=0, 221 tests, cobertura de líneas 91 %) |
| Creado | 2026-10-26 |
| Alcance | Issues #3 y #4: lectura del panel por id, filtro por `estadoRevision` y procedencia de la retracción (`origen_rechazo`) |
| No incluye | Filtro por `estado` (chip «Vendidos»), historial de decisiones de moderación, caché en cliente |
| Presupuesto (heurística ~400 líneas) | ~520 líneas con tests; se entrega en un PR de feature con dos commits de comportamiento (uno por issue) |
| Estrategia de entrega | single-pr |
| Test runner | `mvn -q -B verify` (JUnit 5 + Testcontainers PostgreSQL 18) |
| Documentos | `openapi.yaml` (`/mis-anuncios`, parámetro del filtro, `AnuncioPanel`), `docs/prd.md` (R5, §5), `docs/database-design.md` (§3 columna, §3.2 CHECKs) |

## Specs

- **S1 (#3)** — `GET /v1/mis-anuncios/{anuncioId}` devuelve `AnuncioPanel` con ownership como predicado de repositorio (R7). Anuncio ajeno o inexistente → `404` indistinguible y con el mismo cuerpo. Sin sesión → `401`.
- **S2 (#3)** — `GET /v1/mis-anuncios` acepta `estadoRevision` **aditivo**; sin parámetro mantiene el comportamiento actual. `total` y `totalPaginas` reflejan el filtro.
- **S3 (#3)** — Valores aceptados: `pendiente`, `aprobado`, `rechazado`. Cualquier otro valor → `400` con mensaje explícito (mismo criterio que `orden` en el catálogo).
- **S4 (#4)** — Columna `origen_rechazo` (`rechazo` | `retraccion`, nullable) con backfill de las filas ya rechazadas y dos CHECK: dominio cerrado y `(estado_revision = 'rechazado') = (origen_rechazo IS NOT NULL)`. El enum `EstadoRevision` NO cambia.
- **S5 (#4)** — `ServicioRevision`: `rechazar` escribe `rechazo`, `retraer` escribe `retraccion`, `aprobar` lo limpia; `limpiarTrailRevision` (edición, R6) también lo limpia.
- **S6 (#4)** — Filtro `estadoRevision=retraido` = `rechazado AND origen_rechazo='retraccion'`; `estadoRevision=rechazado` = `rechazado AND origen_rechazo='rechazo'`. Son mutuamente excluyentes.
- **S7 (#4)** — `AnuncioPanel` gana `retraido: boolean` (aditivo). `estadoRevision` del payload sigue con tres valores.
- **S8** — El parámetro del panel es un esquema propio en `openapi.yaml`: el de la cola de moderación no acepta `retraido`.

## Tareas

- [x] **T1 — #3 lectura por id**: `ServicioCatalogo.panelPropio` + ruta en `ControladorAnuncios` + contrato + integración (propio/ajeno/inexistente/401). Ruta: inline.
- [x] **T2 — #3 filtro**: validación del valor, predicado y paginación con total filtrado + contrato + tests. Ruta: inline.
- [x] **T3 — #4 esquema**: `OrigenRechazo` + converter + campo en `Anuncio` + migración `V4` + `DbSchemaVerificationTest` al día. Ruta: inline.
- [x] **T4 — #4 servicio**: escritura/limpieza del origen en `ServicioRevision` y `limpiarTrailRevision`; unit tests de la máquina de estados. Ruta: inline.
- [x] **T5 — #4 wire**: `retraido` en `AnuncioPanel`, valor de filtro en el contrato y semántica excluyente; integración (retraído aparece en `retraido` y no en `rechazado`). Ruta: inline.
- [x] **T6 — Verificación y entrega**: `mvn -q -B verify`, PRD/DBDD sincronizados, commits por issue. Ruta: inline.

## Log

- **L1** — `L1` (2026-10-26, verbatim): «si resuelve las issues ahora» — autorización para implementar #3 y #4.
- **L2** — Issue #3 (verbatim, extracto): «`GET /v1/mis-anuncios/{anuncioId}` → `AnuncioPanel`, con ownership como predicado de repositorio (R7). Anuncio ajeno o inexistente → `404` indistinguible. `GET /v1/mis-anuncios?estadoRevision=pendiente|aprobado|rechazado` como parámetro **aditivo**; sin él, el comportamiento actual. `total`/`totalPaginas` reflejan el filtro.»
- **L3** — Issue #4 (verbatim, extracto): «Migración `V4`: columna `origen_rechazo varchar(20)` nullable + backfill de las filas ya rechazadas a `'rechazo'`, y dos CHECK [...] `ServicioRevision`: `rechazar` → `'rechazo'`; `retraer` → `'retraccion'`; `aprobar` → `NULL`. Filtro: `estadoRevision=retraido` se traduce a `rechazado AND origen_rechazo='retraccion'` [...] `AnuncioPanel` gana `retraido: boolean` (aditivo).»
- **L4** — Evidencia previa (2026-10-26): `retraer` deja `estadoRevision='rechazado'` sin rastro del origen (`ServicioRevision.java:69-73`); `EstadoRevision` tiene tres valores (`EstadoRevision.java`); `NoEncontradoPropio` citado en la issue no existe en el código (se usa `NoEncontradoException`).
- **L5** — Decisión: el parámetro del panel se declara como esquema propio (`EstadoRevisionPanel`) porque `#/components/parameters/EstadoRevision` lo comparte la cola de moderación (`openapi.yaml:898`) y `retraido` no pertenece a ese dominio.
- **L8** — Evidencia #4 (2026-10-26, observada): RED inicial (`retraido` → `400` en el filtro y `retraido` ausente del panel); GREEN con `RetraccionProcedenciaIntegrationTest` 2/2 y `ServicioRevisionTest` ampliado. El CHECK nuevo destapó un fixture inconsistente en `AnuncioRepositoryTest` (rechazaba por repositorio sin procedencia): se corrigió el fixture, no la regla — es exactamente lo que el CHECK debe impedir.
- **L9** — Evidencia (2026-10-26): `mvn -B verify` EXIT=0 · **221 tests** · 0 fallos · cobertura de líneas **91 %** con el gate activo. `DbSchemaVerificationTest` actualizado a V1–V4 y a los 15 CHECKs nombrados.
- **L7** — Evidencia #3 (2026-10-26, observada): RED inicial con la ruta inexistente (`404`) y el parámetro ignorado; GREEN con `PanelVendedorIntegrationTest` 5/5. El 404 indistinguible compara cuerpo normalizado: solo cambia el id dentro de `instance` (el `ProblemDetail` incluye la ruta), no el `title` ni el `detail`.
- **L6** — Decisión: sin índice nuevo. La consulta del panel usa `vendedor_id` (+ `estado_revision`) sobre `idx_anuncios_vendedor`; un índice compuesto queda como entrega aparte si el plan lo justifica con datos reales.
