# Verificación observada · 2 de octubre de 2026

Entorno inicial: Windows, SDK .NET 10.0.401 instalado en `D:/Cursos/.tools/dotnet`, JDK BellSoft 21.0.7, Maven 3.9.10 y Node.js. La ejecución actual está en Docker Desktop en esta laptop; GitHub aloja código, CI y releases.

## Contenedores y despliegue desde Actions

- [Container CI 37041168363](https://github.com/jorgefprietol/system-design-lab/actions/runs/37041168363): construcción de ambas imágenes, **38 comprobaciones HTTP aprobadas**, escaneo, SBOM y publicación correctos. [Reporte conservado en el repositorio](evidence/container-contract-report.json).
- [Release validado](https://github.com/jorgefprietol/system-design-lab/releases/tag/build-ee72959188dcbeb731b018ff2ca13f0c4eeba906), con archivos de imagen, manifiesto y evidencia; [attestation](https://github.com/jorgefprietol/system-design-lab/attestations/52233153).
- El análisis de esta ejecución registró C#: 5 LOW y 9 MEDIUM; Java: 4 LOW y 38 MEDIUM. **0 HIGH/CRITICAL** en ambas imágenes. El gate bloquea HIGH/CRITICAL con corrección disponible. Esto describe el escaneo de esa fecha, no garantiza ausencia de vulnerabilidades futuras.
- [Laptop CD 37042177286](https://github.com/jorgefprietol/system-design-lab-deploy/actions/runs/37042177286): ejecución real aprobada en el runner privado Windows. Verificó checksums y procedencia, respaldó datos y cambió ambos contenedores al SHA `ee72959188dcbeb731b018ff2ca13f0c4eeba906`.
- `/health` de ambos runtimes respondió `up` y el SHA desplegado. Los contenedores están saludables, con usuario 10001 y filesystem de solo lectura.
- La comparación del estado completo antes/después de migrar a Docker y desplegar desde Actions dio igualdad en ambas implementaciones: C# conserva 3 eventos y Java 1. Los historiales nativos originales permanecen disponibles; los backups se guardan fuera del checkout.

Los enlaces anteriores son evidencia de una ejecución concreta. Nuevos commits generan nuevos releases y despliegues. La suite completa dentro de contenedores pasó en GitHub; el intento local bajo la carga concurrente de otros proyectos agotó su tiempo de arranque y no se cuenta como aprobado.

## Compilación

- C#: `dotnet build csharp/SystemDesignLab.csproj --nologo` usando el SDK indicado. Resultado: **0 errores y 0 advertencias**.
- Java: `mvn -q -f java/pom.xml package` con `JAVA_HOME` correspondiente al `java` del PATH. Resultado: salida 0 y JAR ejecutable generado.
- `Start-Lab.ps1 -NoBuild`: ambos endpoints `/health` respondieron con el runtime esperado.
- `Stop-Lab.ps1`: detuvo los dos procesos tras verificar sus rutas; el siguiente arranque recuperó los datos de las operaciones hechas desde el navegador.

## Pruebas compartidas

**38 comprobaciones aprobadas: 19 por implementación.** [Reporte JSON](../tests/results/2026-10-02T16-55-29.366Z/report.json). Cada implementación se probó con datos propios y puerto dinámico.

La suite verifica:

- Proyecciones iniciales, assets, redirección 302 y aciertos de caché.
- Historial de chat con salas y contenido de texto.
- Pedidos idempotentes y conflicto al modificar un payload con la misma clave.
- **20 compradores simultáneos y stock de 5 unidades: exactamente 5 compras y 15 rechazos**, stock final 0.
- Feed con autores propios/seguidos y validación de longitud.
- Selección de taxi cercano, actualización de ubicación sin liberar un conductor ocupado, finalización idempotente.
- **10 solicitudes simultáneas para un solo conductor libre: exactamente 1 asignación y 9 rechazos.**
- Crawler con ciclos y destinos ausentes; recuperación de un job pendiente desde un evento de prueba persistido antes del arranque.
- Historial de archivos, SHA-256, rechazo por propietario, tamaño máximo y **dos ediciones simultáneas: una escritura y un conflicto**.
- JSON malformado, tokens JSON adicionales y base64 no canónico.
- Secuencia de eventos, equivalencia de todas las proyecciones tras reiniciar e idempotencia durable.
- Rechazo de un segundo escritor de la misma implementación.
- Historial corrupto: el arranque falla y no borra ni reinicializa los datos.

No se probaron cortes de energía, réplicas, sharding, WebSocket, broker externo, autenticación, fallos de red entre regiones ni migración del historial entre runtimes.

## Interfaz

Verificación mediante la habilidad Browser: creación de enlace, compra y guardado de una versión de archivo en C#; envío de mensaje en Java. Las operaciones mostraron confirmación y datos desde el backend. No se observaron errores ni warnings de consola en la revisión de la pestaña C#. Se revisó visualmente la página completa: [captura](preview.jpg).

## Medición local de lecturas

Comandos ejecutados: `node scripts/benchmark.mjs http://127.0.0.1:5081 500 10` y equivalente para el puerto 5082. Ambos tests se ejecutaron concurrentemente y compartieron recursos del equipo. Datos distintos y pequeños: C# tenía 3 eventos de la prueba de interfaz; Java, 1. **Estos resultados no permiten concluir que un lenguaje sea más rápido.**

| Runtime | Solicitudes | Concurrencia | Errores | RPS observados | p50 ms | p95 ms | p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| C# | 500 | 10 | 0 | 558.6 | 14.5 | 34.8 | 54.7 |
| Java | 500 | 10 | 0 | 649.8 | 13.9 | 25.7 | 56.9 |

Los tiempos incluyen HTTP y consumo del cuerpo por el cliente Node; no son tiempos exclusivos del motor. [Medición C#](../tests/results/benchmark-csharp.json), [medición Java](../tests/results/benchmark-java.json). No extrapolar a millones de usuarios, datos voluminosos o carga de escrituras.
