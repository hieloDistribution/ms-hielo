# Guía de Resolución de Problemas (Troubleshooting)

Esta guía detalla los problemas más comunes que pueden surgir durante el arranque, desarrollo y prueba del proyecto **HieloPedido**, junto con sus soluciones paso a paso.

---

## 1. El Backend Falla al Iniciar (Fallo en JwtSecretValidator)

### Síntoma
Al ejecutar `./mvnw spring-boot:run` en cualquiera de los dos microservicios, el contexto falla inmediatamente en el arranque imprimiendo logs similares a:
```text
Caused by: java.lang.IllegalStateException: JWT_SECRET environment variable must be set and be at least 32 bytes (256 bits) long
```
O el proceso termina con un código de salida `1` sin levantar el servidor.

### Causa
Como medida de seguridad implementada en `JwtSecretValidator`, los microservicios se niegan a arrancar si la clave simétrica de cifrado para los tokens JWT está ausente, vacía o tiene una longitud inferior a 32 caracteres (256 bits).

### Solución
Debes configurar la variable de entorno `JWT_SECRET` en la terminal antes de ejecutar Maven:
*   **En PowerShell**:
    ```powershell
    $env:JWT_SECRET = "mi_clave_secreta_super_larga_de_mas_de_32_bytes_12345"
    ./mvnw spring-boot:run
    ```
*   **En CMD**:
    ```cmd
    set JWT_SECRET=mi_clave_secreta_super_larga_de_mas_de_32_bytes_12345
    ./mvnw spring-boot:run
    ```
*   **En IntelliJ IDEA o VS Code**:
    Agrega la variable en la configuración de ejecución (Run/Debug Configurations) en el apartado de **Environment Variables**:
    *   Name: `JWT_SECRET`
    *   Value: `mi_clave_secreta_super_larga_de_mas_de_32_bytes_12345`
*   **Mediante parámetros de la JVM (Parámetro inline de Maven)**:
    Si prefieres pasarlo directamente al comando de Maven:
    ```bash
    ./mvnw spring-boot:run -Dspring-boot.run.arguments="--jwt.secret=mi_clave_secreta_super_larga_de_mas_de_32_bytes_12345"
    ```

---

## 2. El Emulador de Android no se Conecta al Backend

### Síntoma
La aplicación móvil se ejecuta en el emulador de Android Studio pero muestra alertas de "Sin conexión" u errores de red (e.g. `ConnectException`, `SocketTimeoutException`) al intentar autenticarse o sincronizar, a pesar de que los microservicios están corriendo en la PC.

### Causa
Si la URL base en el código Flutter está configurada como `localhost` o `127.0.0.1` (e.g., `http://localhost:8081`), la conexión fallará. El emulador Android es una máquina virtual con su propio adaptador de red; para el emulador, `localhost` se refiere al teléfono virtual mismo, no a la PC host.

### Solución
1.  Modifica la URL de sincronización en el proveedor del frontend [order_provider.dart](file:///D:/programacion/UI-HieloPedido/lib/providers/order_provider.dart):
    *   Cambia la IP a la IP virtual especial del host de Android: **`10.0.2.2`**.
    ```dart
    static const String _syncApiUrl = 'http://10.0.2.2:8081/api/v1/sync';
    ```
2.  Si estás depurando con un **dispositivo Android físico** (celular conectado por USB):
    *   Asegúrate de que el celular y la PC estén en la **misma red Wi-Fi**.
    *   Averigua la IP local de tu PC ejecutando `ipconfig` en la terminal (ejemplo: `192.168.1.15`).
    *   Coloca esa dirección IP en el archivo Dart:
    ```dart
    static const String _syncApiUrl = 'http://192.168.1.15:8081/api/v1/sync';
    ```

---

## 3. Error en la Base de Datos al Iniciar (Docker / PostgreSQL)

### Síntoma
Spring Boot muestra errores de conexión a base de datos (`Connection refused`) o Hibernate no encuentra las tablas requeridas.

### Solución
1.  **Verificar el estado del contenedor**:
    Ejecuta `docker ps` y asegúrate de que el contenedor `sales-postgres` está activo en el puerto `5432`.
2.  **Verificar si el puerto 5432 está ocupado**:
    Si tienes otra instancia local de PostgreSQL corriendo nativamente en tu PC, Docker Compose fallará al intentar mapear el puerto `5432`. Debes detener tu servicio Postgres local:
    *   *Windows*: Abre `Servicios` (services.msc), busca `postgresql` y haz clic en Detener. Luego reintenta levantar Docker.
3.  **Bases de datos faltantes**:
    El script [init.sql](file:///d:/programacion/ice/init-scripts/init.sql) inicializa los esquemas `sync_db` y `order_db` la primera vez que se levanta el contenedor. Si ya tenías un volumen de PostgreSQL creado anteriormente, Docker omitirá este paso.
    Si te faltan las bases de datos, puedes forzar la recreación limpiando los volúmenes antiguos:
    ```bash
    docker-compose down -v
    docker-compose up -d
    ```
    *Advertencia: Esto borrará todos los datos almacenados localmente en PostgreSQL.*

---

## 4. Cierre de Sesión Repentino al Utilizar Múltiples Dispositivos o Peticiones Concurrentes

### Síntoma
Un vendedor es desconectado repentinamente de la aplicación móvil y forzado a iniciar sesión de nuevo, mostrando el error `token_revoked` en la consola o logs de red.

### Causa
Este comportamiento es el resultado del mecanismo de protección contra robo de tokens por **rotación de tokens de refresco (Theft Detection)**. 
Si el cliente móvil realiza dos peticiones concurrentes de refresco utilizando el mismo `refresh_token` (por ejemplo, debido a reintentos automáticos rápidos por fallos temporales de red o un error en el código de hilos del cliente), la segunda petición presentará un token que el servidor ya marcó como inactivo en la primera petición. El servidor interpreta esto como un ataque de replay (un token de refresco robado siendo reutilizado por un tercero) y, por seguridad, **invalida toda la familia de tokens asociada al usuario**.

### Solución
*   **En el Cliente**: Asegura que el proceso de renovación del token de refresco esté sincronizado/bloqueado (usando un *Mutex* o un estado de renovación global) para que nunca se disparen dos renovaciones concurrentes.
*   **Manejo**: Si el backend retorna el código de error `token_revoked` con estado `401`, la aplicación debe borrar de inmediato cualquier token almacenado en las preferencias locales y redirigir limpiamente al usuario a la pantalla de inicio de sesión.
