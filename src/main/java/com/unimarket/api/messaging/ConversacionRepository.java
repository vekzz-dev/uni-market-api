package com.unimarket.api.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/** Repositorio de conversaciones: el par {@code (anuncioId, compradorId)} lo impone la UNIQUE (R9). */
public interface ConversacionRepository extends JpaRepository<Conversacion, UUID>, JpaSpecificationExecutor<Conversacion> {

    Optional<Conversacion> findByAnuncioIdAndCompradorId(UUID anuncioId, UUID compradorId);
}
