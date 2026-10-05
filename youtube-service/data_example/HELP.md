# Getting Started

## Descargas de YouTube

Instala `yt-dlp`, `ffmpeg` y Deno 2.3.0 o superior y asegúrate de que estén disponibles en el `PATH`.
La integración habilita explícitamente el runtime JavaScript Deno y descarga los componentes EJS oficiales
desde GitHub (`--remote-components ejs:github`). Estos valores se pueden cambiar con
`toolbox.youtube.js-runtime` y `toolbox.youtube.ejs-remote-components`. Los ejecutables oficiales standalone
de yt-dlp ya incluyen EJS; la opción remota también permite obtener scripts actuales.
En Windows, Deno se puede instalar con `winget install DenoLand.Deno`; comprueba la instalación con
`deno --version` y reinicia IntelliJ/la aplicación para que herede el `PATH` actualizado.
La aplicación convierte el mejor audio disponible a MP3 con calidad de codificación `0`.
Los nombres de archivo siguen el formato `<título> --- <videoId>.mp3` (con caracteres no admitidos por el
sistema de archivos reemplazados por `_`). Por defecto, guarda los MP3 en
`music` y el historial JSON en `.data/youtube/history`;
la carpeta base se puede cambiar con `toolbox.youtube.storage-directory`.
Cada registro incluye `downloadTimeMillis`, el tiempo transcurrido en milisegundos para descargar y convertir
ese vídeo de forma individual. El historial se guarda en un archivo JSON independiente por día UTC,
`.data/youtube/history/yyyy-MM-dd.json`; cada nueva canción se añade de forma serializada y se escribe
mediante un reemplazo atómico para evitar perder entradas concurrentes. El formato agrupado anterior y los
archivos legacy por descarga se migran automáticamente al formato diario al leer o modificar el historial.

### Servicios independientes y Gateway

El repositorio contiene dos aplicaciones Spring Boot independientes, cada una con su propio `pom.xml`:

* `youtube-service`: API de YouTube, puerto `8081`.
* `gateway`: Gateway público, puerto `8080`; reenvía `/api/{servicio}/...` al servicio configurado y reúne los
  documentos OpenAPI de los servicios configurados en una sola especificación.

En Windows, `../.data/youtube/start-atalaya.ps1` permite arrancar los tres procesos en terminales separadas desde cualquier
directorio: `& 'C:\ruta\al\proyecto\.data\start-atalaya.ps1'`. También acepta `-Start Youtube`, `-Start Gateway`,
`-Start Music` o `-Start All`. El script selecciona Java 27 solo en las terminales Maven que crea, usa la
configuración privada de Maven de `%USERPROFILE%\.m2\settings.xml` y no cambia el entorno global ni el del
proyecto Java 8. Antes de iniciar los servicios, conecta la VPN necesaria y verifica que las credenciales del
repositorio privado estén configuradas en `settings.xml`. Puedes personalizar las rutas con `-JavaHome`,
`-MavenSettings` o las variables `ATALAYA_JAVA_HOME` y `ATALAYA_MAVEN_SETTINGS`. `MAVEN_OPTS` contiene
opciones de la JVM, no selecciona la versión de Java; el script evita heredar opciones del otro proyecto y permite
configurarlas explícitamente con `-MavenOpts` o `ATALAYA_MAVEN_OPTS`. También fija
`TOOLBOX_YOUTUBE_STORAGE_DIRECTORY` a `.data/youtube` en la raíz del proyecto para que Spring encuentre la
biblioteca y el historial independientemente del directorio de trabajo interno de Maven.

Ejecuta Maven desde la raíz apuntando al POM de cada aplicación:
`mvn -f youtube-service/pom.xml spring-boot:run` y
`mvn -f gateway/pom.xml spring-boot:run` en terminales separadas. Para construir o probar una aplicación,
usa `mvn -f <servicio>/pom.xml package` o `mvn -f <servicio>/pom.xml test`.
La UI de Swagger del Gateway está en `http://localhost:8080/swagger-ui/index.html`; su documento agregado está en
`http://localhost:8080/gateway/openapi.json`.

No se requiere registro de servicios ni infraestructura externa. El Gateway carga las direcciones desde
`toolbox.gateway.services` en `../../gateway/src/main/resources/application.yaml`; por defecto YouTube apunta a
`http://localhost:8081`, configurable con `YOUTUBE_SERVICE_URL`. Para añadir un servicio hermano, agrega otra
entrada con un `base-url` (puede ser una URL DNS local, por ejemplo `http://catalog-service:8082`) y su
`openapi-path`, normalmente `/v3/api-docs`. El Gateway consulta cada documento al construir el OpenAPI agregado,
pone namespace a los componentes y etiquetas para evitar colisiones y falla explícitamente si encuentra rutas
duplicadas.

La API de YouTube recibe una URL de vídeo o playlist:

```http
POST /api/youtube/download
Content-Type: application/json

{"url":"https://www.youtube.com/watch?v=..."}
```

Consulta las canciones registradas con `GET /api/youtube/history`.
Desde Spring Shell se pueden usar `youtube download --url <URL>` y `youtube history`.
La biblioteca de MP3 se explora desde la interfaz Angular independiente en `../../music-library`. Esta aplicación
agrupa las canciones por carpetas, carga cada carpeta al expandirla y ofrece controles de reproducción y
shuffle junto a una consola para los ocho endpoints del servicio. Los modos shuffle recorren toda la biblioteca
o una carpeta con la profundidad seleccionada y vuelven a barajar al terminar cada ciclo. El árbol JSON está en
`GET /api/youtube/music/tree`; admite `path=<ruta-relativa>` y `nestedFolderDepth=<0..32>` para consultar
solo una rama o limitar los niveles descendientes (`0` incluye el contenido directo). Sin profundidad se
devuelve el árbol completo. La reproducción usa `GET /api/youtube/music/track?path=<ruta-relativa>`.
La escucha registra cada nuevo inicio de reproducción (no las pausas/reanudaciones) en
`.data/music-player-listens.json`; `POST /api/youtube/music/listens` registra una reproducción y
`GET /api/youtube/music/most-listened` devuelve las canciones ordenadas por número de escuchas.
Los temas personalizables se almacenan en `../.data/youtube/music-player-theme.json` y se administran con
`GET/POST /api/youtube/player-theme`, `PUT /api/youtube/player-theme/{themeId}`,
`DELETE /api/youtube/player-theme/{themeId}` y `PUT /api/youtube/player-theme/selection`.
Con Node.js 20.19+ o 22.12+, ejecuta `npm install` y `npm start` desde `music-library/`. El servidor de
desarrollo reenvía `/api/youtube` al servicio local en el puerto `8081` y escucha en todas las interfaces de
red. En el PC, abre `http://localhost:4200`; desde un teléfono conectado a la misma red local, abre
`http://<IP-IPv4-del-PC>:4200` (la IP se muestra al iniciar mediante `start-atalaya.ps1 -Start Music` o
`-Start All`; también se puede consultar con `ipconfig`). Si no carga, permite el puerto TCP `4200` en el
Firewall de Windows para el perfil privado y asegúrate de que el teléfono no esté en una Wi-Fi de invitados
con aislamiento de clientes. El servidor de desarrollo no debe exponerse a Internet.
Para playlists grandes, `POST /api/youtube/playlist/prepare` acepta la petición con HTTP 202 y devuelve
inmediatamente el identificador de playlist, el estado `preparing` y la ruta del archivo de metadata. La
consulta a yt-dlp se realiza en segundo plano; al terminar, se guarda
`.data/youtube/prepared-playlists/<playlistId>.json` y se lanza un job de Spring Batch para descargar sus
canciones. El job procesa bloques de cuatro y realiza hasta tres intentos por canción con espera creciente; solo
elimina del JSON las canciones descargadas con éxito. Spring Batch guarda su repositorio JDBC en
`.data/youtube/batch-metadata.mv.db`; el JSON preparado sigue siendo la fuente de verdad de las canciones
pendientes. Consulta el estado de la preparación con
`GET /api/youtube/playlist/prepare/status?playlistId=<playlistId>`; sus estados incluyen `preparing`,
`prepared`, `complete` y `failed` (con el error disponible en ese último caso).

Tras un reinicio, o para reintentar fallos, `POST /api/youtube/playlist/process?playlistId=<playlistId>`
reanuda una playlist concreta; sin el parámetro, encola todas las playlists preparadas con canciones
pendientes. El procesamiento no se reanuda automáticamente al arrancar la aplicación.
La petición puede incluir `"destination":"pokemon/rejuvenation"` para guardar las canciones bajo esa subcarpeta
relativa a `music`; si se omite, se usa la raíz de `music`. El Gateway reenvía tanto esta API como las futuras
APIs hermanas según el nombre configurado en `toolbox.gateway.services`.

### Reference Documentation

For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.1.1/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.1.1/maven-plugin/build-image.html)
* [GraalVM Native Image Support](https://docs.spring.io/spring-boot/4.1.1/reference/packaging/native-image/introducing-graalvm-native-images.html)
* [Spring Batch](https://docs.spring.io/spring-boot/4.1.1/how-to/batch.html)
* [Spring Configuration Processor](https://docs.spring.io/spring-boot/4.1.1/specification/configuration-metadata/annotation-processor.html)
* [Spring Boot DevTools](https://docs.spring.io/spring-boot/4.1.1/reference/using/devtools.html)
* [Apache Freemarker](https://docs.spring.io/spring-boot/4.1.1/reference/web/servlet.html#web.servlet.spring-mvc.template-engines)
* [JDBC API](https://docs.spring.io/spring-boot/4.1.1/reference/data/sql.html)
* [Spring Modulith](https://docs.spring.io/spring-modulith/reference/)
* [Spring gRPC Client](https://docs.spring.io/spring-grpc/reference/client.html)
* [Spring gRPC Server](https://docs.spring.io/spring-grpc/reference/server.html)
* [HTTP Client](https://docs.spring.io/spring-boot/4.1.1/reference/io/rest-client.html#io.rest-client.restclient)
* [Spring Shell](https://docs.spring.io/spring-shell/reference/index.html)
* [SpringDoc OpenAPI](https://springdoc.org/)
* [Thymeleaf](https://docs.spring.io/spring-boot/4.1.1/reference/web/servlet.html#web.servlet.spring-mvc.template-engines)
* [Validation](https://docs.spring.io/spring-boot/4.1.1/reference/io/validation.html)
* [Spring Web](https://docs.spring.io/spring-boot/4.1.1/reference/web/servlet.html)

### Guides

The following guides illustrate how to use some features concretely:

* [Creating a Batch Service](https://spring.io/guides/gs/batch-processing/)
* [Accessing Relational Data using JDBC with Spring](https://spring.io/guides/gs/relational-data-access/)
* [Managing Transactions](https://spring.io/guides/gs/managing-transactions/)
* [SpringDoc OpenAPI](https://github.com/springdoc/springdoc-openapi-demos/)
* [Handling Form Submission](https://spring.io/guides/gs/handling-form-submission/)
* [Validation](https://spring.io/guides/gs/validating-form-input/)
* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)

### Additional Links

These additional references should also help you:

* [Configure AOT settings in Build Plugin](https://docs.spring.io/spring-boot/4.1.1/how-to/aot.html)
* [Various sample apps using Spring gRPC](https://github.com/spring-projects/spring-grpc/tree/main/samples)
* [Various sample apps using Spring gRPC](https://github.com/spring-projects/spring-grpc/tree/main/samples)

## GraalVM Native Support

This project has been configured to let you generate either a lightweight container or a native executable.
It is also possible to run your tests in a native image.

### Lightweight Container with Cloud Native Buildpacks

If you're already familiar with Spring Boot container images support, this is the easiest way to get started.
Docker should be installed and configured on your machine prior to creating the image.

To create the image, run the following goal:

```
$ mvn -f youtube-service/pom.xml spring-boot:build-image -Pnative
```

Then, you can run the app like any other container:

```
$ docker run --rm -p 8081:8081 youtube-service:0.0.1-SNAPSHOT
```

### Executable with Native Build Tools

Use this option if you want to explore more options such as running your tests in a native image.
The GraalVM `native-image` compiler should be installed and configured on your machine.

NOTE: GraalVM 25+ is required.

To create the executable, run the following goal:

```
$ mvn -f youtube-service/pom.xml native:compile -Pnative
```

Then, you can run the app as follows:

```
$ youtube-service/target/youtube-service
```

You can also run your existing tests suite in a native image.
This is an efficient way to validate the compatibility of your application.

To run your existing tests in a native image, run the following goal:

```
$ mvn -f youtube-service/pom.xml test -PnativeTest
```
