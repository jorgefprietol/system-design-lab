# Ejercicios guiados

Cada ejercicio empieza por requisitos funcionales y no funcionales, declara supuestos de carga, identifica invariantes, dibuja arquitectura, verifica localmente y propone evolución. Los casos están implementados en C# y Java; repite los mismos pasos en ambos puertos.

1. **Acortador:** crea un enlace, abre la redirección dos veces y comprueba un acierto de caché. Espera más de 30 s y observa otra consulta fallida a caché. Reinicia y verifica que el enlace persiste. Diseña expiración e invalidación sin reutilizar códigos.
2. **Chat:** abre dos pestañas del mismo runtime, envía mensajes y observa el polling. Cambia de sala y comprueba el filtro. Propón cursor, deduplicación de envío y reconexión con WebSocket. Define qué significa enviado, recibido y leído.
3. **E-commerce:** compra dos teclados con la misma clave dos veces: el pedido y el stock cambian una sola vez. Cambia la cantidad sin cambiar la clave y observa 409. Ejecuta la suite para 20 compras simultáneas sobre 5 libros. Propón pago fallido, devolución, reservas y compensaciones.
4. **Red social:** publica como `lucas`, cambia a `ana` y pulsa «Seguir a lucas». El feed incluye autores seguidos y propios. Propón cursor de paginación, bloqueo, borrado de posts y fan-out para un autor con millones de seguidores.
5. **Taxis:** registra el conductor de ejemplo, solicita un viaje y verifica que no se ofrece para otro. Completa y vuelve a solicitar. Diseña expiración de oferta, aceptación por conductor y reasignación bajo fallo de red.
6. **Crawler:** encola el ejemplo que contiene ciclos. El worker termina con cuatro páginas únicas. Diseña reinicio entre la descarga y el ack, rate limit por host y una cola de errores. No hay crawler de Internet en esta implementación.
7. **Archivos:** guarda versión 1, edita con expectedVersion 1 y conserva versión 2. Intenta otra edición con versión 1 y observa el conflicto. Consulta `/api/files/notas?version=1`. Propón blobs externos, multipart, permisos y recuperación de versiones.

Para comparar rendimiento, ejecutar `benchmark.mjs` sobre cada puerto con los mismos datos, mezcla, número de solicitudes y concurrencia. Registrar p50, p95, p99 y throughput. El percentil no sustituye el análisis de errores ni la saturación; una prueba de lectura pequeña no mide escrituras ni alta disponibilidad.

Para practicar CAP, describe una partición entre dos regiones y decide por operación: ¿leer un feed ligeramente antiguo o fallar?, ¿permitir dos reservas del mismo conductor o rechazar una petición? Describe las garantías con precisión y prueba el fallo en un entorno distribuido antes de afirmar que se cumplen.
