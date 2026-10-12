package com.unimarket.api.shared.pagination;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Estado del cursor compuesto para {@code orden=precio} (D4): tupla {@code (precio, id)}.
 * El token sigue siendo opaco para el cliente; el id desempata precios iguales.
 */
public record RegistroCursorPrecio(BigDecimal precio, UUID id) {
}
