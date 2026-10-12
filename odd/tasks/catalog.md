# Feature: catalog

| Campo | Valor |
|---|---|
| Estado | Completada (2026-10-12; 119 tests verdes, cobertura 84.8% con gate activo) |
| Creado | 2026-10-12 |
| Alcance | Módulo `catalog` (SDD §4): anuncios del vendedor + catálogo público + detalle + panel "mis anuncios" + `GET /v1/categorias`; extensión del cursor para orden por precio; límites de imágenes en `PropiedadesMedia` |
| No incluye | Moderación (decisiones R5), conversaciones, CRUD de categorías (módulo `admin`), adaptador R2 |
| Presupuesto (heurística ~400 líneas) | ~1600 líneas con tests — excede a propósito: es el corazón del producto (R4/R6/R7/R8 + O3–O4); se entrega en un PR de feature |
| Estrategia de entrega | single-pr |
| Documentos | `docs/prd.md` (R4, R6, R7, R8 + §5 Catálogo/Anuncios), `docs/database-design.md` (§3 anuncios, §3.1 índices, §5), `docs/system-design.md` (§5.3, §6 cursor, D4, D14), `openapi.yaml` (paths /anuncios* , /mis-anuncios, /categorias GET) |

## Decisiones de diseño de esta feature

- **Cursor compuesto por criterio de orden**: por defecto `(fechaCreacion, id)`; con `orden=precio`, `(precio, id)`. Se extiende `CursorCodec` (shared) con variante de precio (`{"p":"<decimal-plano>","i":"<uuid>"}`); el token sigue opaco y el wire no cambia. Al cierre se sincronizan la descripción del parámetro en `openapi.yaml` y la nota del SDD §6.
- **Imágenes**: claves `anuncios/{vendedorId}/{uuid}.{ext}` vía `AlmacenObjetos`; arrays `imagenes_urls`/`imagenes_paths` pareados por posición escritos en la MISMA transacción que la fila (R8); mapeo JSONB nativo de Hibernate 7 (`@JdbcTypeCode(SqlTypes.JSON)`); la respuesta expone **solo `imagenUrls`** — las claves jamás salen al wire (test explícito).
- **Compensación**: subir objetos ANTES del commit; si la tx falla → borrar los subidos. En edición: subir nuevos → commit → borrar reemplazados (los viejos solo tras commit exitoso).
- **Ciclo de vida**: nace `pendiente` sin excepción de rol (R5); editar `aprobado|rechazado` → `pendiente` + limpieza del trail (R6); visibilidad solo si `aprobado` (≠ → 422) alterna `disponible↔inactivo`; vendido desde `disponible|inactivo`; baja = `estado=eliminado` conservando objetos y conversaciones.
- **Ownership** (R7): predicado en repositorio para "mis anuncios" (nunca filas ajenas) + verificación en servicio antes de mutar (403 ajeno; 404 si no existe).
- **En tests**, la aprobación se simula manipulando `estadoRevision`/`estado` vía repositorio: la API de moderación llega en la siguiente feature. Documentado en los propios tests.
- Búsqueda `q`: `ILIKE` sobre nombre y descripción con escape de `%`, `_`, `\` (trigram del DBDD acelera); `q` en blanco se trata como ausente.
- Límites de subida en `PropiedadesMedia` (una sola fuente, R8): anuncio 5 imágenes, 5 MB c/u, tipos jpeg/png/gif/webp.

## Tareas

- [x] **T1 — Modelo**: `Anuncio` (JPA, JSONB nativo `@JdbcTypeCode(SqlTypes.JSON)` — funcionó a la primera, enums por converter, `@PreUpdate`), repositorio + `Specification`s (visibilidad R4, ownership R7, búsqueda con escape) + límites en `PropiedadesMedia` + yml (main y test). Ruta: **inline** (delegación denegada dos veces por permisos del runtime; ver nota).
- [x] **T2 — Cursor precio**: variante `(precio, id)` en `CursorCodec` + `RegistroCursorPrecio` + `CursorCodecPrecioTest` (4 tests: roundtrip, opacidad, malformados, rechazo cruzado). Ruta: inline.
- [x] **T3 — Crear anuncio**: multipart con validación por **firma de bytes** (jpeg/png/gif/webp), 5 MB c/u, máx 5; arrays pareados en una tx; compensación testeada (repo falla → elimina subidos). Ruta: inline.
- [x] **T4 — Mutaciones**: PATCH contenido (reset R6 + limpieza de trail), visibilidad (solo aprobado; además exige estado disponible|inactivo), vendido, DELETE lógico. Guards 403/404 verificados. Ruta: inline.
- [x] **T5 — Lecturas**: catálogo con cursor por orden (fecha DESC / precio ASC), detalle 404 indistinguible, mis-anuncios offset+total (R7), categorías (JdbcTemplate). Ruta: inline.
- [x] **T6 — Tests**: repo 7, servicio 12 (mocks), slice 2, integración 7 (HTTP + PG real). La integración destapó que `*IT` NO lo corre surefire por defecto → renombrada a `CatalogoFlujoIntegrationTest`. Ruta: inline.
- [x] **T7 — Verificación y commit**: `mvn clean verify` EXIT=0; commits `feat(catalog)` + `docs:` de sincronización. Ruta: padre (inline).

## Desviaciones y notas (2026-10-12)

1. **Delegación denegada**: dos intentos de writer fueron rechazados por permisos del runtime (`subagent`); el propietario eligió implementación inline. (El módulo se guardó como work-unit commits normales.)
2. **Mapeo manual** (fábricas estáticas `de(...)`) en vez de MapStruct (D10), consistente con `identity`; D10 actualizada en el SDD para reflejar la práctica real.
3. **Imágenes en edición no implementadas**: el contrato (`PATCH /contenido`) es JSON sin multipart, así que el reemplazo de imágenes que asume el PRD §7 queda como **N6** en el SDD (decidir extender contrato o retirar del PRD). La compensación del alta SÍ está implementada y testeada.
4. **Orden por precio ascendente** (contrato ambiguo en dirección) con cursor `(precio, id)` mayor.
5. **`ApiExceptionHandler` +2 handlers** (`ServletRequestBindingException`, `MultipartException` → 400): sin ellos, una parte multipart faltante caía al fallback 500 (defecto real detectado por el slice test).
6. **Convención de slices**: `StubsParaSlicesContextCustomizerFactory` (solo test) extendida con `ServicioAnuncios`/`ServicioCatalogo` — los `@WebMvcTest` genéricos escanean TODOS los controladores.
7. **Spring 7**: `HttpStatus.UNPROCESSABLE_CONTENT` reemplaza a `UNPROCESSABLE_ENTITY` (mismo 422).
8. **Nota de seguridad**: la URL pública `/media/anuncios/{vendedorId}/{uuid}.ext` contiene el path del objeto por diseño (la clave interna viaja solo como campo `imagenesPaths`, nunca expuesto — test explícito).
9. **Divergencia aditiva del wire (menor)**: el listado público incluye `estado` y `fechaActualizacion` (el schema `AnuncioResumen` del contrato no los lista; son campos extra compatibles). Si se exige espejo estricto, se alinea en la próxima pasada del contrato; ningún cliente se rompe hoy.
10. **Fix post-auditoría (2026-10-12)**: `max-request-size` del contenedor era 10 MB mientras R8 permite 5×5 MB por anuncio — subir 3+ imágenes válidas daba 400 del contenedor. Corregido a 26 MB (5×5 + margen) en `application.yml` y el yml de test.

## Criterios de aceptación

- `mvn -q -B verify` verde con el gate de cobertura ≥ 80 % activo.
- Cada guard con caso permitido y denegado (R7: ajeno 403; público no visible 404 indistinguible).
- Ninguna respuesta contiene claves de storage; `imagenes_urls` y `imagenes_paths` quedan pareados en BD tras crear y tras editar.
- Subidas rechazadas (422) no dejan objetos en el storage (verificado en el directorio del adapter filesystem).
- ArchUnit y contrato OpenAPI siguen verdes.
