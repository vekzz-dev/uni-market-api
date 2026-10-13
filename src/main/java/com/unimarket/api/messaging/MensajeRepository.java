package com.unimarket.api.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Repositorio de mensajes: historial inmutable con cursor, no leídos y marcado explícito. */
public interface MensajeRepository extends JpaRepository<Mensaje, UUID>, JpaSpecificationExecutor<Mensaje> {

    Optional<Mensaje> findTopByConversacionIdOrderByFechaCreacionDescIdDesc(UUID conversacionId);

    /** No leídos PARA el usuario: recibidos (de la contraparte) y pendientes de lectura. */
    long countByConversacionIdAndLeidoFalseAndRemitenteIdNot(UUID conversacionId, UUID usuarioId);

    /** Marcado explícito: solo los recibidos del usuario; un GET jamás muta {@code leido} (PRD §5). */
    @Modifying(clearAutomatically = true)
    @Query("update Mensaje m set m.leido = true "
            + "where m.conversacionId = :conversacionId and m.remitenteId <> :usuarioId and m.leido = false")
    int marcarLeidos(@Param("conversacionId") UUID conversacionId, @Param("usuarioId") UUID usuarioId);
}
