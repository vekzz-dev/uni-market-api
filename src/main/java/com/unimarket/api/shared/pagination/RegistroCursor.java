package com.unimarket.api.shared.pagination;

import java.time.Instant;
import java.util.UUID;

/**
 * Estado compuesto {@code (fechaCreacion, id)} que ordena las consultas paginadas (D4):
 * el {@code id} desempata timestamps iguales y garantiza orden total estable, sin filas
 * saltadas ni duplicadas ante inserciones concurrentes.
 */
public record RegistroCursor(Instant fechaCreacion, UUID id) {
}
