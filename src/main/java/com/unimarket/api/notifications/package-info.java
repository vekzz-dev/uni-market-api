/**
 * Emails de confirmación y reset: envío post-commit asíncrono con backoff y reenvío con cooldown.
 * Nunca revierte el flujo HTTP; adaptador Resend (prod) y adaptador de log (dev).
 */
package com.unimarket.api.notifications;
