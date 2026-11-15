package com.unimarket.api.moderation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Denuncias (R19): una por persona y anuncio (UNIQUE), cola FIFO por estado y conteo de
 * abiertas por anuncio para el panel de moderación.
 */
public interface DenunciaRepository extends JpaRepository<Denuncia, UUID>, JpaSpecificationExecutor<Denuncia> {

    /** Atajo para responder 409 sin depender de la violación de integridad (que sigue siendo la autoridad). */
    boolean existsByAnuncioIdAndDenuncianteId(UUID anuncioId, UUID denuncianteId);

    /** Denuncias abiertas del anuncio: se cierran cuando el moderador decide sobre él. */
    List<Denuncia> findByAnuncioIdAndEstado(UUID anuncioId, EstadoDenuncia estado);

    /** Abiertas por anuncio para la página del panel, en UNA consulta. */
    @Query("select d.anuncioId as anuncioId, count(d) as total from Denuncia d "
            + "where d.anuncioId in :anuncioIds and d.estado = com.unimarket.api.moderation.EstadoDenuncia.abierta "
            + "group by d.anuncioId")
    List<ConteoDenuncias> contarAbiertasPorAnuncio(@Param("anuncioIds") Collection<UUID> anuncioIds);

    /** Proyección del conteo agrupado por anuncio. */
    interface ConteoDenuncias {

        UUID getAnuncioId();

        long getTotal();
    }
}
