# Atlas · System Design Lab

Proyecto independiente inspirado en los temas y ejercicios del curso «Diseño de Sistemas a Gran Escala y Arquitectura de Software». Implementación original; no requiere vídeos, recursos privados ni acceso a Udemy. Convierte el temario de diseño en siete experimentos ejecutables con **C#/.NET 10** y **Java 21**.

Las versiones tienen el mismo contrato HTTP, usan la misma interfaz web y almacenan sus propios datos. No dependen de `banking-architecture-lab` ni de `advanced-architecture-lab`.

## Casos implementados

| Sistema | Operaciones reales | Conceptos practicados |
| --- | --- | --- |
| Acortador | Crear enlace, redirigir, consultar aciertos de caché | Cache aside, TTL, identificadores únicos |
| Chat | Publicar mensajes, consultar historial por sala desde la interfaz | Persistencia, proyecciones, polling |
| E-commerce | Comprar, reintentar sin duplicar, rechazar sobreventa | Idempotencia durable, concurrencia, centavos enteros |
| Red social | Publicar, seguir, consultar feed | CQRS, relaciones, composición de consultas |
| Taxis | Registrar ubicación, reservar el más cercano, completar | Haversine, exclusión mutua, estados |
| Crawler | Encolar y procesar un grafo con ciclos | Worker asíncrono, cola persistida, BFS |
| Archivos | Subir, descargar, consultar versiones y checksums | Event sourcing, concurrencia optimista, SHA-256 |

## Ejecutar en Windows

La ejecución predeterminada utiliza contenedores y el despliegue está preparado para esta laptop. Requisito: Docker Desktop activo.

```powershell
cd D:\Cursos\system-design-lab
.\scripts\Start-Containers.ps1
```

Consulta [CI/CD y operación local](docs/ci-cd.md). El pipeline público prueba y publica imágenes por commit; el repositorio privado de operaciones verifica y despliega las imágenes en esta laptop. Los datos se conservan en volúmenes Docker independientes.

### Alternativa nativa para depurar

Requisitos: SDK .NET 10.0.401 (o parche compatible), JDK 21+, Maven y PowerShell. Node.js se utiliza únicamente en las pruebas y mediciones. El lanzador aprovecha `../.tools/dotnet/dotnet.exe` cuando existe; en otro equipo utiliza `dotnet` del PATH. Maven descarga Jackson y sus herramientas durante la primera compilación.

```powershell
cd D:\Cursos\system-design-lab
.\scripts\Start-Lab.ps1
```

- C#: <http://127.0.0.1:5081>
- Java: <http://127.0.0.1:5082>

Seleccionar una versión: `Start-Lab.ps1 -Implementation CSharp` o `-Implementation Java`. Después de compilar, `-NoBuild` reutiliza los binarios. Detener: `Stop-Lab.ps1` (también admite `-Implementation`). Detén los procesos antes de recompilar o volver a ejecutar el lanzador.

Los procesos se ejecutan en segundo plano. Los logs y PID están en `data/logs`. El script de parada verifica que cada PID corresponda al ejecutable del proyecto antes de detenerlo.

## Ejecutar manualmente en cualquier plataforma

Desde `csharp`: `dotnet run`. Desde `java`: `mvn package` y `java -jar target/system-design-lab-1.0.0.jar`. En ambos casos la raíz predeterminada es el directorio padre.

Variables opcionales: `LAB_ROOT` (ruta absoluta del proyecto), `LAB_DATA` (directorio de eventos) y `LAB_PORT`. Ambos servidores escuchan en `127.0.0.1`. C# usa ASP.NET Core; Java usa el servidor HTTP del JDK, virtual threads y Jackson, sin framework web adicional.

## Verificar

```powershell
.\scripts\Test-Lab.ps1
node scripts/benchmark.mjs http://127.0.0.1:5081 500 10
node scripts/benchmark.mjs http://127.0.0.1:5082 500 10
```

La suite arranca procesos aislados en puertos libres y conserva evidencia bajo `tests/results`. Comprueba los siete casos, concurrencia real por HTTP, validación, reinicios y bloqueo de escritor. El benchmark mide throughput y percentiles p50/p95/p99 de lecturas locales.

## Alcance

Es un laboratorio de **un nodo**. El historial se guarda como un array JSON reemplazado mediante archivo temporal; cada escritura recorre el historial completo y las proyecciones se mantienen en memoria. Los nombres de usuario son declarados y no equivalen a autenticación. El chat usa polling, el crawler procesa un grafo suministrado y los archivos están limitados a 64 KiB. No se ha probado capacidad para millones de usuarios.

Los patrones de diseño se implementan a escala local; balanceadores, gateways externos, brokers, CDN, replicación, sharding, múltiples datacenters y microservicios distribuidos se desarrollan como diseños y ejercicios en [docs/architecture.md](docs/architecture.md). La separación comando/consulta comparte proceso y bloqueo; no es una infraestructura CQRS distribuida.

Consulta [el contrato HTTP](docs/api.md), [los ejercicios](docs/exercises.md) y [los resultados observados](docs/verification.md).
