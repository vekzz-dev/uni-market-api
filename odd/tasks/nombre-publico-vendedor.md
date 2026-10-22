# Feature: nombre-publico-vendedor

| Campo | Valor |
|---|---|
| Estado | Completada (2026-10-21; `mvn -B verify` EXIT=0, 212 tests, cobertura de líneas 91 %) |
| Creado | 2026-10-21 |
| Alcance | Issue #2 (GitHub): `vendedorNombre` en el catálogo público; regla de negocio en identity; contrato `openapi.yaml`; PRD R17 |
| No incluye | Endpoint público `GET /v1/usuarios/{id}`, mostrar el nombre completo, cambios de esquema o migración Flyway (no hacen falta: el dato ya vive en `usuarios.nombre`) |
| Presupuesto (heurística ~400 líneas) | ~460 líneas con tests; excede a propósito: regla de negocio nueva + contrato + costura identity→catalog; se entrega en un PR de feature |
| Estrategia de entrega | single-pr |
| Test runner | `mvn -q -B verify` (JUnit 5 + Testcontainers PostgreSQL) |
| Documentos | `docs/prd.md` (R17, §5 Catálogo público), `docs/system-design.md` (§4 dependencias `catalog → identity`), `openapi.yaml` (`AnuncioResumen`) |

## Specs

- **S1** — `AnuncioResumen` expone `vendedorNombre` (string, máximo 60) junto a `vendedorId`; `AnuncioDetalle` y `AnuncioPanel` lo heredan por `allOf`. Es aditivo y el campo siempre viaja.
- **S2** — El servidor calcula el nombre público como **nombre de pila + inicial del último apellido**: `"Ana Pérez"` → `"Ana P."`. Nunca envía el nombre completo.
- **S3** — El catálogo (listado) resuelve los nombres **en lote** (`findAllById`), sin N+1: N anuncios de M vendedores ⇒ 1 consulta de usuarios. El detalle y el panel resuelven uno.
- **S4** — La regla vive en `identity`, dueño de `usuarios.nombre`, y se expone como servicio de aplicación (`catalog` importa solo el servicio expuesto, SDD §4).
- **S5** — Fallback sin filtrar identidad: cuenta inexistente, dada de baja (`fechaEliminacion` no nula) o con nombre en blanco ⇒ `"Vendedor"` (R1: las lecturas filtran las cuentas dadas de baja).
- **S6** — Contrato (`openapi.yaml`) y PRD (R17) documentan el campo y la regla; ningún cambio de esquema ni migración.

## Tareas

- [x] **T1 — Regla + servicio (identity)**: `NombresPublicos` (regla pura) y `ServicioNombresPublicos` (batch + single, `findAllById`, filtra inactivas). RED observado: `mvn -Dtest=NombresPublicosTest test` falla por símbolo inexistente; GREEN: 9 tests de regla + 4 de servicio. Ruta: inline.
- [x] **T2 — Contrato y PRD**: `vendedorNombre` en `AnuncioResumen` (required, maxLength 60) + R17 en el PRD + §5 + criterio de aceptación #18. `OpenApiContractTest` verde (spec válida, sin claves duplicadas). Ruta: inline.
- [x] **T3 — Catalog**: DTOs con el campo, `MapeadorAnuncios` (resolución en lote) y wiring en `ServicioCatalogo`, `ServicioAnuncios`, `ServicioRevision`; `MapeadorAnunciosTest` (3), slice de contrato (9/11 campos) e integración `NombreDelVendedor` (2, contra PostgreSQL real). Ruta: inline.
- [x] **T4 — Verificación y entrega**: `mvn -B verify` EXIT=0 — 212 tests, gate de cobertura cumplido (91 % de líneas). SDD §4 sincronizado, aceptación #18 mapeada. Ruta: inline.

## Log

- **L1** — `L1` (2026-10-21, verbatim): «si abordala de la forma correcta» — tras el análisis de la issue #2.
- **L2** — Issue #2 (verbatim, extracto): «Añadir `vendedorNombre` (string, máximo 60) a `AnuncioResumen`; `AnuncioDetalle` y `AnuncioPanel` lo heredan por `allOf`. El servidor lo calcula como **nombre de pila + inicial del último apellido** ("Ana Pérez" → "Ana P.") y nunca envía el nombre completo. El catálogo resuelve los nombres en lote (`findAllById`) para no introducir N+1; el detalle carga uno.»
- **L3** — Evidencia (2026-10-21): la issue marca `[x] Contrato openapi.yaml`, pero `openapi.yaml` no contiene `vendedorNombre` y `AnuncioResumen` sigue con `required: [id, nombre, precio, vendedorId, fechaCreacion]`. La casilla se corrige al cerrar.
- **L4** — Decisión (asumption, S5): sin nombre resoluble se publica `"Vendedor"`; exponer el nombre de una cuenta dada de baja choca con R1 (toda lectura filtra `fechaEliminacion IS NULL`) y con el espíritu de R15. Se documenta en R17.
- **L5** — Decisión: no hay migración Flyway ni cambio de esquema; el dato ya está en `usuarios.nombre` y el cálculo es de lectura.
- **L6** — Decisión: `catalog → identity` es la dirección ya declarada en el SDD §4 («`catalog` necesita `identity`»), y ArchUnit (`modulesShouldBeFreeOfCycles`) sigue verde porque `identity` no importa `catalog`.
- **L7** — Evidencia (2026-10-21, observada): RED de la regla = compilación fallida por símbolo `NombresPublicos` inexistente; GREEN con 9 casos. `mvn -B verify` EXIT=0 · 212 tests · 0 fallos · cobertura de líneas **91 %** (gate ≥ 80 % cumplido).
- **L8** — Hallazgo del test E2E: la primera versión esperaba `"Ana P."` para `"Ana María Pérez Gómez"`; la regla literal («inicial del ÚLTIMO apellido») da `"Ana G."`. Se corrigió la expectativa del test, no la regla.
- **L9** — Nota de contrato: `%20` manual en `q` se re-codifica y no encuentra nada; los tests existentes pasan la consulta con espacios y `TestRestTemplate` la codifica. Se siguió esa convención.
