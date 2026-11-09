package com.unimarket.api.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repositorio de mensajes: historial inmutable con cursor, no leídos y marcado explícito. */
public interface MensajeRepository extends JpaRepository<Mensaje, UUID>, JpaSpecificationExecutor<Mensaje> {

    /**
     * Último mensaje de cada conversación de la página en UNA consulta (antes: una por fila).
     * "Último" = ninguno más nuevo por {@code (fechaCreacion, id)}, el mismo desempate que el cursor.
     */
    @Query("select m from Mensaje m where m.conversacionId in :conversacionIds "
            + "and not exists (select otro.id from Mensaje otro "
            + "where otro.conversacionId = m.conversacionId "
            + "and (otro.fechaCreacion > m.fechaCreacion "
            + "or (otro.fechaCreacion = m.fechaCreacion and otro.id > m.id)))")
    List<Mensaje> ultimosPorConversacion(@Param("conversacionIds") Collection<UUID> conversacionIds);

    /** No leídos por conversación de la página en UNA consulta (antes: una por fila). */
    @Query("select m.conversacionId as conversacionId, count(m) as total from Mensaje m "
            + "where m.conversacionId in :conversacionIds and m.leido = false and m.remitenteId <> :usuarioId "
            + "group by m.conversacionId")
    List<ConteoNoLeidos> noLeidosPorConversacion(
            @Param("conversacionIds") Collection<UUID> conversacionIds, @Param("usuarioId") UUID usuarioId);

    /** Proyección del conteo agrupado por conversación. */
    interface ConteoNoLeidos {

        UUID getConversacionId();

        long getTotal();
    }

    /** No leídos PARA el usuario: recibidos (de la contraparte) y pendientes de lectura. */
    long countByConversacionIdAndLeidoFalseAndRemitenteIdNot(UUID conversacionId, UUID usuarioId);

    /** Marcado explícito: solo los recibidos del usuario; un GET jamás muta {@code leido} (PRD §5). */
    @Modifying(clearAutomatically = true)
    @Query("update Mensaje m set m.leido = true "
            + "where m.conversacionId = :conversacionId and m.remitenteId <> :usuarioId and m.leido = false")
    int marcarLeidos(@Param("conversacionId") UUID conversacionId, @Param("usuarioId") UUID usuarioId);
}
