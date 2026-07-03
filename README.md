# HieloPedido - Sistema de Pedidos Offline-First

Este es el repositorio principal para el sistema de registro y sincronización de pedidos offline-first para la distribución de hielo. El sistema está diseñado para que los vendedores registren pedidos en la calle (incluso sin conectividad celular) y los sincronicen automáticamente al recuperar internet.

El sistema consta de:
1. **Cliente Móvil (Frontend)**: Desarrollado en Flutter, ubicado en `D:\programacion\UI-HieloPedido` (repositorio independiente).
2. **Servicio de Sincronización e Idempotencia (`sync-service`)**: Microservicio Spring Boot en Java 21, encargado de la puerta de entrada, la validación de tokens JWT, control de idempotencia y rotación de tokens de refresco.
3. **Servicio de Pedidos y Catálogo (`order-service`)**: Microservicio Spring Boot en Java 21 que administra la base de datos transaccional central de pedidos, validación de stock y catálogo de productos.
4. **Base de Datos**: PostgreSQL 15 ejecutándose a través de Docker.

---

## 📂 Estructura del Proyecto

```text
ice/
├── android-spec/          # Especificaciones de código nativo Kotlin para Android (Room, DAOs, Worker)
├── docker-compose.yml     # Orquestación de la base de datos PostgreSQL
├── init-scripts/          # Scripts SQL de inicialización para Docker
├── openspec/              # Especificación del diseño Spec-Driven Development (SDD) para autenticación
├── order-service/         # Microservicio Core de Pedidos (Puerto 8082)
├── sync-service/          # Microservicio de Sincronización e Idempotencia (Puerto 8081 o 8080)
├── PRD.md                 # Documento de Requerimientos del Producto (Product Requirements Document)
└── docs/                  # Documentación extendida
    ├── ARCHITECTURE.md    # Arquitectura detallada del sistema (Outbox, JWT, etc.)
    ├── API_REFERENCE.md   # Referencia detallada de Endpoints y payloads de error
    └── TROUBLESHOOTING.md # Guía para resolver problemas comunes de ejecución
```

---

## ⚡ Guía de Inicio Rápido

Sigue estos pasos en orden para levantar todo el ecosistema de desarrollo local:

### 1. Iniciar la Base de Datos (PostgreSQL)
Asegúrate de tener Docker instalado y corriendo. En la raíz de este proyecto (`d:\programacion\ice`), ejecuta:
```bash
docker-compose up -d
```
Esto creará las bases de datos `sync_db` y `order_db` en el puerto `5432` con usuario/contraseña `postgres/postgres`.

### 2. Configurar la Variable de Entorno JWT
El sistema utiliza firmas HMAC-SHA256 para tokens JWT. **Ambos servicios se negarán a iniciar** si la variable de entorno `JWT_SECRET` no está configurada o si tiene menos de 32 bytes (256 bits).
* **Windows (PowerShell)**:
  ```powershell
  $env:JWT_SECRET="mi_clave_secreta_super_larga_de_mas_de_32_bytes_12345"
  ```
* **Windows (CMD)**:
  ```cmd
  set JWT_SECRET=mi_clave_secreta_super_larga_de_mas_de_32_bytes_12345
  ```

### 3. Ejecutar los Microservicios Backend
Abre dos terminales diferentes e inicia cada servicio mediante Maven Wrapper:
* **Microservicio 2: Core Pedidos**
  ```bash
  cd order-service
  ./mvnw spring-boot:run
  ```
* **Microservicio 1: Sincronización e Idempotencia**
  ```bash
  cd sync-service
  ./mvnw spring-boot:run
  ```

### 4. Configurar y Correr el Cliente Flutter
Abre el proyecto `D:\programacion\UI-HieloPedido` en **Android Studio**.
1. Instala las dependencias:
   ```bash
   flutter pub get
   ```
2. Configura la dirección IP local de tu máquina en [order_provider.dart](file:///D:/programacion/UI-HieloPedido/lib/providers/order_provider.dart) (usa `10.0.2.2` si corres en el emulador de Android Studio).
3. Inicia tu emulador Android o conecta tu celular por USB.
4. Presiona **Run** en Android Studio.

---

## 📖 Documentación Detallada

Para más información sobre el funcionamiento del sistema, consulta los siguientes archivos:

*   [Guía de Arquitectura](file:///d:/programacion/ice/docs/ARCHITECTURE.md): Explicación técnica del patrón Outbox Transaccional y la estrategia de seguridad con JWT.
*   [Referencia de APIs](file:///d:/programacion/ice/docs/API_REFERENCE.md): Detalles de endpoints de autenticación, pedidos y sincronización de datos.
*   [Guía de Troubleshooting](file:///d:/programacion/ice/docs/TROUBLESHOOTING.md): Soluciones rápidas a errores comunes en base de datos, puertos o inicio de sesión.
