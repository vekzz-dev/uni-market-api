package com.unimarket.api.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/** Repositorio de anuncios: las reglas de visibilidad/ownership viajan como Specifications (R4/R7). */
public interface AnuncioRepository extends JpaRepository<Anuncio, UUID>, JpaSpecificationExecutor<Anuncio> {
}
