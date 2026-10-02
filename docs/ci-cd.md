# Contenedores y CI/CD en la laptop

Código público: [system-design-lab](https://github.com/jorgefprietol/system-design-lab). Control de despliegue privado: [system-design-lab-deploy](https://github.com/jorgefprietol/system-design-lab-deploy). El repositorio privado es accesible por el propietario; el runner registrado en él no se comparte con el código público.

## Ejecución predeterminada

```powershell
.\scripts\Start-Containers.ps1
```

Docker Desktop construye ambos ejecutables en etapas independientes y arranca sus imágenes runtime. No se requiere instalar .NET, Java o Maven en el host para ejecutar esta ruta. Ambas apps incluyen el frontend y exponen solo localhost: C# en 5081 y Java en 5082. No hay un servidor frontend adicional fuera de Docker.

Migración inicial de los datos usados por procesos nativos: `Start-Containers.ps1 -ImportNativeData`. Detiene solamente los procesos de este laboratorio, copia el historial si el volumen todavía está vacío y conserva los archivos originales. No sobrescribe un historial de contenedores existente.

Contenedores con usuario 10001, filesystem de solo lectura, capabilities eliminadas, sin escalada de privilegios, límite de 512 MiB / 1 CPU / 128 procesos, tmpfs de 64 MiB, healthcheck y reinicio `unless-stopped`. No montan el socket Docker ni carpetas del usuario. Datos en volúmenes `atlas-csharp-data` y `atlas-java-data`. Logs rotados hasta 3 archivos de 10 MiB por servicio.

La red de Atlas utiliza `10.203.75.0/24`, comprobada libre en esta laptop. Evita depender de los pools automáticos agotados por otros proyectos. En otro equipo, revisar que esta subred no se solape con sus redes existentes.

## CI público

`Container CI` corre en `ubuntu-24.04` administrado por GitHub para pushes a main y pull requests. Ninguna tarea del repositorio público utiliza la laptop.

1. Validar sintaxis de frontend y Compose.
2. Construir C# y Java desde imágenes base fijadas por digest.
3. Ejecutar la suite compartida HTTP contra contenedores con datos aislados.
4. Generar SBOM CycloneDX y reporte de vulnerabilidades. Bloquear HIGH/CRITICAL con corrección disponible; las vulnerabilidades sin fix también se registran en el reporte completo.
5. Para main, empaquetar las imágenes ya probadas en archivos Docker comprimidos y manifiesto con SHA del commit, ids de imagen y checksums.
6. Generar attestations de procedencia con identidad OIDC de GitHub y publicar un release `build-<sha completo>` con evidencia.

Actions y herramientas críticas se fijan por digest/SHA. Dependabot propone actualizaciones de Actions, Docker y Maven. Solo el job de publicación tiene permisos de escritura de contenido, identidad OIDC y attestations. Los PR no publican releases.

## CD privado

Un runner Windows de este equipo ejecuta el workflow privado `Laptop CD`. Consulta cada cinco minutos si main tiene una nueva versión validada; GitHub puede retrasar las ejecuciones programadas. El despliegue también puede iniciarse manualmente. El modelo no requiere guardar un token permanente de amplia autorización en un repositorio público para contactar al repositorio privado.

El pipeline privado valida CI, release, SHA-256, attestations, identidad de las imágenes y usuario no root. Solo usa su propio Compose y scripts de operación confiables. Detiene escrituras, crea respaldos locales, recrea las dos apps por id inmutable y comprueba estado e interfaz por HTTP. Ante un fallo vuelve a las imágenes anteriores. No revierte eventos automáticamente: una restauración de datos requiere revisar qué escrituras se perderían.

La instalación es de un nodo con un escritor por historial: hay una interrupción breve durante el cambio de versión. `previous.json` permite solicitar el release anterior. El estado y los backups están bajo `%LOCALAPPDATA%/Atlas/system-design-lab`, fuera del checkout del runner.

## Operación diaria

- Mantener la laptop encendida, sesión Windows iniciada, red disponible y Docker Desktop activo.
- El runner se conecta hacia GitHub; no requiere abrir puertos entrantes ni exponer el servidor a Internet.
- El runner se inicia mediante un acceso de inicio de sesión en la carpeta Startup del usuario.
- Suspender/apagar la laptop detiene disponibilidad y despliegues. Al volver a iniciar sesión, el runner regresa y Docker recupera contenedores cuando el daemon está activo.
- Después del primer despliegue, detener/reiniciar desde el repositorio privado conservando sus ids de imagen:

  ```powershell
  cd D:\Cursos\system-design-lab-deploy
  $atlasEnv = Join-Path $env:LOCALAPPDATA 'Atlas\system-design-lab\current.env'
  docker compose --env-file $atlasEnv stop
  docker compose --env-file $atlasEnv up -d --no-build --wait
  ```

- No usar `down --volumes` si se quiere conservar el historial.
- Después de habilitar CD, usar Actions para desplegar y rollback. Una compilación manual de desarrollo no es un release validado.

Despliegue manual: `gh workflow run deploy.yml --repo jorgefprietol/system-design-lab-deploy`. Para volver al release anterior validado: agregar `-f rollback=true`. Consultar el resultado en Actions; un job programado puede finalizar sin cambios si todavía no hay un nuevo release validado.

## Referencias

- [Seguridad de runners y workflows, GitHub](https://docs.github.com/en/actions/reference/security/secure-use): justifica mantener el runner fuera del repositorio público.
- [Programación de workflows, GitHub](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax): intervalo mínimo de cinco minutos; sin garantía de ejecución inmediata.
- [Volúmenes Docker](https://docs.docker.com/engine/storage/volumes/): datos independientes del ciclo de vida de un contenedor.
- [Servicios de Compose](https://docs.docker.com/reference/compose-file/services/): restricciones de runtime y comprobaciones de salud.
