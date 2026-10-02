# Contrato compartido

Requests y respuestas JSON UTF-8. Comandos exitosos: 200. Errores de negocio: `{"error":"code"}` con 422 (validación), 409 (conflicto), 403 (propietario distinto) o 404 (recurso ausente). JSON inválido: 400. Cuerpo máximo: 128 KiB. Los campos extra no se interpretan.

| Método / ruta | Cuerpo o resultado |
| --- | --- |
| GET `/health` | `status`, `implementation` |
| GET `/api/state` | Proyecciones de los siete casos, eventCount, cacheHits, cacheMisses |
| GET `/api/events` | Últimos 100 eventos, incluyendo datos del laboratorio |
| POST `/api/links` | `{"url":"https://example.com"}` |
| GET `/r/{id}` | 302, `Location` hacia el destino; caché 30 s |
| POST `/api/messages` | `{"room":"general","user":"ana","text":"Hola"}` |
| POST `/api/orders` | `{"key":"pedido-1","product":"book","quantity":1}` |
| POST `/api/posts` | `{"user":"lucas","text":"Publicación"}` |
| POST `/api/follows` | `{"user":"ana","target":"lucas"}` |
| GET `/api/feed/{user}` | Hasta 100 posts, del más reciente al más antiguo |
| POST `/api/drivers` | `{"id":"d1","lat":-2.17,"lon":-79.92}` |
| POST `/api/rides` | `{"user":"ana","lat":-2.17,"lon":-79.92}` |
| POST `/api/rides/{id}/complete` | `{}` |
| POST `/api/crawl` | `{"root":"a","pages":{"a":["b"],"b":["a"]}}` |
| POST `/api/files` | `{"id":"notes","user":"ana","name":"notes.txt","expectedVersion":0,"content":"SG9sYQ=="}` |
| GET `/api/files/{id}?version=1` | Versión con contenido base64, bytes y SHA-256; omitir versión o usar 0 devuelve la actual |

Los ids generados son cadenas opacas. `book` cuesta 2500 centavos y comienza con 5 unidades; `keyboard`, 6500 centavos y 8 unidades. El stock se reconstruye desde los pedidos del historial.

Las órdenes son idempotentes por `key`; cambiar producto o cantidad con la misma clave devuelve `idempotency_conflict`. Los follows repetidos y completar un viaje terminado también son idempotentes. Crear links, mensajes, posts, viajes o trabajos de crawler no tiene clave de reintento: repetir el comando crea otro registro.

Los archivos exigen base64 canónico con padding cuando corresponde, hasta 65536 bytes. La primera escritura declara `expectedVersion:0`; la segunda, `1`. El nombre no se utiliza como ruta en disco. Los endpoints de lectura son públicos en el entorno local; la comprobación de propietario durante escrituras es una regla didáctica, no un control de identidad autenticada.

El crawler admite hasta 100 páginas, cada una con hasta 100 enlaces; ignora destinos ausentes, visita cada página una vez y conserva el orden BFS. Procesa un trabajo cada segundo. La persistencia es previa a la respuesta; al fallar una escritura el evento no se aplica. El worker vuelve a intentar los trabajos que permanezcan pendientes.

Las dos versiones prueban el mismo subconjunto del contrato. Las reglas de parsing de URL, Unicode y errores del servidor HTTP pueden variar entre runtimes para entradas fuera de los ejemplos y la suite.
