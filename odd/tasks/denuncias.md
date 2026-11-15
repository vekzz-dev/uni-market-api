# Feature: denuncias

| Campo | Valor |
|---|---|
| Estado | Completada (2026-11-15; `mvn verify` EXIT=0, 265 tests, cobertura con gate) |
| Creado | 2026-11-15 |
| Alcance | Issue #9: denuncias de anuncios por miembros, cola de moderación, resolución auditada y contador por anuncio |
| No incluye | Ocultado automático por umbral, notificaciones al vendedor, historial de denuncias por usuario |
| Estrategia de entrega | single-pr |
| Documentos | `openapi.yaml`, `docs/prd.md` (R19, §5, criterio 22), `docs/database-design.md` (§3 tabla, §3.1 índices, §3.2 CHECKs), `docs/system-design.md` (§4) |

## Specs

- **S1** — `POST /v1/anuncios/{anuncioId}/denuncias` con `{motivo, detalle?}`: `201`. Requiere sesión (`miembro` o superior) → `401` sin token.
- **S2** — Motivos cerrados: `prohibido`, `falsificacion`, `estafa`, `spam`, `inadecuado`, `otro`. `detalle` obligatorio no vacío solo con `otro` (`422` si falta); en el resto es opcional y máximo 500.
- **S3** — Solo se denuncia un anuncio **visible**: no visible o inexistente → `404` indistinguible (R4). El propio anuncio → `422`.
- **S4** — Una denuncia por persona y anuncio: `UNIQUE (anuncio_id, denunciante_id)`; el reintento → `409`. Rate limit por IP y usuario.
- **S5** — La denuncia **no actúa**: no retira, ni oculta, ni cambia la revisión del anuncio (R5 intacto).
- **S6** — Cola de moderación con `denunciasAbiertas` por anuncio, calculado en UNA consulta por página.
- **S7** — `GET /v1/moderacion/denuncias?estado=` FIFO (`fechaCreacion ASC, id ASC`) y `POST /v1/moderacion/denuncias/{id}/resolucion` con `revisada|descartada`; una denuncia ya resuelta → `422`.
- **S8** — Retraer o rechazar el anuncio cierra sus denuncias abiertas como `revisada` con auditoría del moderador.
- **S9** — Privacidad: el denunciante nunca aparece en ninguna respuesta (ni en la cola de denuncias ni en el panel del vendedor); solo se publica el conteo.

## Tareas

- [x] **T1 — Esquema**: `V8` con tabla `denuncias`, UNIQUE por persona/anuncio, 4 CHECK y 2 índices (`anuncio_id, estado` y `estado, fecha_creacion, id`); `DbSchemaVerificationTest` al día (9 tablas, 20 índices, 21 CHECKs, V1–V8).
- [x] **T2 — Dominio**: `Denuncia`, `MotivoDenuncia`, `EstadoDenuncia`, convertidores; `resolver` y `cerrarPorDecisionDelAnuncio` con guardas de estado.
- [x] **T3 — Servicio**: `ServicioModeracion` en `moderation` (dependencia `moderation → catalog`, sin ciclo): denunciar, cola con contador, resolver y decidir cerrando denuncias.
- [x] **T4 — HTTP**: `ControladorDenuncias` (miembro) y rutas de moderación; contrato con `PanelModeracion` y `DenunciaPanel` sin `denuncianteId`.
- [x] **T5 — Tests**: `DenunciasFlujoIntegrationTest` (6): alta, duplicado 409, propio 422, no visible 404, anónimo 401, `otro` sin detalle, contador en la cola, resolución única y cierre al retraer.
- [x] **T6 — Verificación**: `mvn verify` 265 tests; 1 fallo propio corregido (el test asumía la posición 0 de una cola FIFO compartida).

## Log

- **L1** — `L1` (2026-11-15, verbatim): «si» — autorización para implementar #9 con los tres ajustes acordados (DTO propio en moderación, cierre de denuncias en el servicio de moderación, índice con `id`) manteniendo el `409`.
- **L2** — Decisión: el contador de denuncias **no** se añade a `AnuncioPanel` (catalog) porque obligaría a `catalog → moderation` y, con `moderation → catalog` ya existente, ArchUnit detectaría un ciclo. Se crea `PanelModeracion` en moderation copiando los campos para conservar la forma plana del wire.
- **L3** — Decisión: el cierre de denuncias al retraer/rechazar ocurre en `ServicioModeracion.decidir` (envuelve a `ServicioRevision`), no dentro de catalog.
- **L4** — Decisión: `409` para el duplicado, como fija la issue (el resto del sistema usa idempotencia; se documenta la diferencia en la issue).
- **L5** — Evidencia: RED por el slice `@WebMvcTest` que no conocía `ServicioModeracion` (convención de `StubsParaSlicesContextCustomizerFactory`); registrado el stub. Segundo fallo propio: el test del contador asumía la posición 0 de una cola FIFO compartida; ahora busca la fila por id.
- **L6** — Evidencia: `mvn verify` EXIT=0, 265 tests. Sondas manuales vía test de integración: contador 2 en la cola, denunciante ausente en el JSON, retraer deja la denuncia en `revisada` y fuera de la cola de abiertas.
