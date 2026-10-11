/**
 * Puerto de almacenamiento de objetos y adaptador S3-compatible, con claves
 * `anuncios/{vendedorId}/{uuid}.{ext}`. Solo expone URLs públicas; las claves jamás salen al wire (R8).
 */
package com.unimarket.api.media;
