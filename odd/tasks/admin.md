# Feature: admin

| Campo | Valor |
|---|---|
| Estado | Completada (2026-10-12; 171 tests, cobertura 91.1% — todos los módulos de negocio cerrados) |
| Creado | 2026-10-12 |
| Alcance | Módulo `admin` (SDD §4): CRUD de categorías (moderador) y gestión de usuarios (admin: listado, cambio de rol R14, habilitar/inhabilitar R15). Último módulo de negocio |
| No incluye | Borrado físico de usuarios ni escritura de `fechaEliminacion` desde el panel (R15), métricas de moderación (fuera de alcance) |
| Presupuesto | ~900 líneas con tests; single-pr |
| Documentos | `docs/prd.md` (R14/R15, §5 Administración), `docs/database-design.md` (§3 categorias SET NULL), `docs/system-design.md` (§4), `openapi.yaml` (paths `/admin/usuarios*`, `/categorias` POST/PATCH/DELETE) |

## Decisiones de diseño

- **Patrón de frontera ya establecido** (igual que `ServicioRevision`): la lógica de usuarios vive en `identity` como servicio expuesto (`ServicioAdministracionUsuarios`); el módulo `admin` aporta controllers + DTOs de request. Así se evita el ciclo `admin↔identity` y el repositorio de `usuarios` nunca se importa fuera de su módulo.
- **Categorías por JDBC** (consistente con la lectura pública de catalog): `ServicioAdminCategorias` sobre `JdbcTemplate`; `orden = MAX(orden)+1`; `nombre` duplicado → `409` (pre-chequeo + carrera capturada); `icono` ≤ 10 y `nombre` no vacío → `422`; borrado físico y los anuncios sobreviven con `categoriaId` nulo (`ON DELETE SET NULL`, DBDD §5).
- **R14**: cambio de rol SOLO admin; rol propio → `403`; rol fuera del dominio o igual al actual → `422` (excepción declarada del contrato); el efecto es inmediato por D3 (la autoridad de rol vive en la BD).
- **R15**: el panel lista solo cuentas activas (`fechaEliminacion IS NULL`); inhabilitar corta login/refresh/lecturas con `401` indistinguible (el filtro de autoridad ya lo aplica); NO existe ningún camino que escriba `fechaEliminacion` desde el panel.
- Roles: `/admin/**` → `@PreAuthorize hasRole('ADMIN')`; CRUD de categorías → `hasAnyRole('MODERADOR','ADMIN')`.
- Stubs de slices extendidos (convención): `ServicioAdministracionUsuarios` + `ServicioAdminCategorias`.

## Tareas

- [x] **AD1 — Servicio de usuarios (identity)**: `ServicioAdministracionUsuarios` (listado de activos con filtro de rol + offset, `cambiarRol` R14 con dominio cerrado case-insensitive, `cambiarHabilitacion` R15, bajas invisibles → 404) + `UsuarioAdminRespuesta`. `UsuarioRepository` ahora extiende `JpaSpecificationExecutor` (filtro de panel). Ruta: inline.
- [x] **AD2 — Categorías (admin)**: `ServicioAdminCategorias` (JDBC: `MAX+1`, 409 por pre-chequeo y por carrera, 422 de nombre/icono, 404, DELETE físico con SET NULL). Ruta: inline.
- [x] **AD3 — Controllers**: `ControladorAdminUsuarios` (clase anotada `hasRole('ADMIN')`) y `ControladorCategoriasAdmin` (moderador); el GET público de categorías sigue en catalog. Stubs de slices extendidos. Ruta: inline.
- [x] **AD4 — Tests**: unit 6/6 (dominio del rol, sin auto-promoción, igual → 422, baja invisible, habilitación) + E2E 5/5: **efecto inmediato probado con el MISMO token** (miembro 403 → ascender → 200 en moderación sin relogin), inhabilitar corta la sesión viva (401) y re-habilitar restaura, listado con filtro y bajas excluidas, crear categoría continúa `MAX+1`, 409/422/403, DELETE con anuncio sobreviviente `categoria_id NULL`. Ruta: inline.
- [x] **AD5 — Verificación y commit**: `mvn clean verify` EXIT=0 (171 tests, 91.1 %, gate activo); commit `feat(admin)`. Ruta: padre.

## Evidencia (2026-10-12)

- Con este módulo quedan cerrados los 8 módulos del SDD §4. Próximo hito: checklist final contra los 17 criterios de aceptación del PRD §8.

## Criterios de aceptación

- Efecto inmediato del cambio de rol y de la inhabilitación (sin esperar expiración del token) probado por API.
- `fechaEliminacion` no escribible desde el panel (no existe camino en código; lista excluye bajas).
- Categorías: `orden` continúa el máximo, duplicado 409, límites 422, borrado conserva anuncios.
- `mvn clean verify` EXIT=0 con cobertura ≥ 80 %; ArchUnit, contrato y 160 suites previas verdes.
