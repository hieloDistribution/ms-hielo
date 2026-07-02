# PRD - Sistema de Registro de Pedidos Offline-First

Este documento define el diseño arquitectónico y los requerimientos del producto para el sistema de registro de pedidos offline-first para vendedores. El sistema consta de una aplicación cliente Android y dos microservicios Java en el backend.

---

## 1. Arquitectura General del Sistema

El flujo de datos sigue el **Patrón Outbox Transaccional** para garantizar la entrega confiable de mensajes ("at-least-once" delivery con idempotencia en el servidor) y una experiencia fluida para el vendedor sin depender de la conectividad en tiempo real.

```mermaid
graph TD
    subgraph Dispositivo Android (Vendedor)
        UI[Pantalla de Pedidos] -->|Crear/Modificar| Room[(Base de Datos Room - SQLite)]
        Room -->|Atómico: Orden + Outbox| Outbox[(Tabla Outbox)]
        WorkManager[WorkManager / CoroutineWorker] -->|Lee en orden cronológico| Outbox
        WorkManager -->|Verifica Conexión TCP Port 53| Internet{¿Hay Internet?}
    end

    subgraph Backend (Microservicios Java)
        Internet -->|HTTP POST /api/v1/sync con UUID| MS1[Microservicio 1: Servicio de Sincronización e Idempotencia]
        MS1 -->|1. Verifica UUID| DBIdemp[(PostgreSQL - Tabla Idempotencia)]
        MS1 -->|2. Envía Pedido HTTP POST| MS2[Microservicio 2: Servicio de Pedidos y Base de Datos Real]
        MS2 -->|Procesa y Consolida Pedido| DBPedidos[(PostgreSQL - Tabla Pedidos)]
        MS1 -->|Consulta Estado Real| MS2
    end
```

---

## 2. Microservicios Backend (Java)

Se diseñarán dos microservicios independientes construidos con **Spring Boot 3.x** y **Java 21**, utilizando una arquitectura estructurada por capas (**Package by Layers**).

### Estructura de Paquetes Común (Package by Layers)
```text
src/main/java/com/sales/service/
│
├── controller/     # Capa de Presentación (REST Controllers, DTOs de entrada/salida)
├── service/        # Capa de Negocio (Interfaces e Implementaciones de lógica de negocio)
├── repository/     # Capa de Acceso a Datos (Interfaces Spring Data JPA)
└── model/          # Capa de Dominio (Entidades JPA del modelo de datos)
```

### Microservicio 1: Sync & Idempotency Service (Servicio de Sincronización)
Este microservicio es la puerta de entrada para los clientes móviles. Su principal responsabilidad es recibir los lotes del Outbox, asegurar la idempotencia y redirigir el procesamiento al servicio core.

*   **Responsabilidades**:
    *   Exponer endpoints REST para la sincronización de operaciones de pedidos.
    *   **Control de Idempotencia**: Verificar si un `client_order_id` (UUID) ya fue procesado mediante una consulta rápida a una tabla de auditoría en la base de datos PostgreSQL (`processed_requests`).
    *   Delegar la creación del pedido al Microservicio 2 de forma síncrona mediante peticiones HTTP REST.
*   **API Endpoints**:
    *   `POST /api/v1/sync`: Recibe un listado de mutaciones del Outbox.
    *   `GET /api/v1/sync/status/{client_order_id}`: Consulta el estado de procesamiento de un pedido enviado.

### Microservicio 2: Core Order & Database Service (Servicio de Pedidos y Base de Datos Real)
Este microservicio interactúa con la base de datos transaccional central. Procesa el negocio real (validación de stock, almacenamiento de pedidos, etc.).

*   **Responsabilidades**:
    *   Gestionar el catálogo de productos y clientes.
    *   Procesar pedidos confirmados y consolidarlos en la base de datos PostgreSQL.
    *   Exponer APIs para que el vendedor consulte el estado actualizado y "real" de sus pedidos y el catálogo cuando tenga conexión.
*   **API Endpoints**:
    *   `POST /api/v1/orders`: Crea un pedido definitivo procesado.
    *   `GET /api/v1/orders/{order_id}`: Obtiene el detalle consolidado de un pedido.
    *   `GET /api/v1/catalog`: Descarga de catálogo optimizada para caché local en el dispositivo.

---

## 3. Estrategia de Sincronización del Cliente Android

El cliente Android debe seguir la especificación técnica offline-first para asegurar la consistencia y evitar la pérdida de datos.

### 3.1. Persistencia Local (SQLite + Room)
*   **Base de Datos Local (Móvil)**: Se utiliza **SQLite** (a través de la abstracción de Jetpack **Room**) debido a que es un motor de base de datos embebido ligero y sumamente rápido, ideal para almacenamiento local en el teléfono.
*   **Claves Primarias UUID**: Todos los pedidos y sus detalles creados en el dispositivo deben usar un UUID generado por el cliente como clave primaria. Esto evita colisiones de ID secuenciales al sincronizarse desde múltiples teléfonos.
*   **Transacción Única**: Toda escritura de pedido se realiza bajo una transacción de Room que:
    1. Inserta o actualiza el pedido en la tabla `orders` (y sus ítems en `order_items`).
    2. Inserta un registro de mutación en la tabla `outbox` con la estructura correspondiente.

```sql
-- Estructura de la Tabla Outbox local
CREATE TABLE outbox (
    id TEXT PRIMARY KEY,           -- UUID de la mutación
    entity_type TEXT,              -- "ORDER"
    entity_id TEXT,                -- UUID del pedido afectado
    operation TEXT,                -- "CREATE", "UPDATE", "DELETE"
    payload TEXT,                  -- Estado del objeto en JSON (ej: el pedido completo)
    timestamp INTEGER,             -- System.currentTimeMillis()
    status TEXT DEFAULT 'PENDING'  -- 'PENDING', 'PROCESSING', 'FAILED'
);
```

*   **Eliminaciones Blandas (Soft Deletes)**:
    *   Si el usuario elimina un pedido localmente, la tabla `orders` se actualiza con `is_deleted = true`.
    *   Se inserta un registro en `outbox` con `operation = 'DELETE'`.
    *   El pedido solo se elimina físicamente de la base de datos local SQLite después de que el backend confirme la recepción del delete.

### 3.2. Detección de Conectividad Real
*   **NetworkCallback**: Suscripción reactiva mediante `ConnectivityManager`.
*   **Verificación Lógica (Falso Positivo)**:
    *   Antes de iniciar la sincronización, se realiza una prueba rápida abriendo un Socket TCP directo al DNS de Google (`8.8.8.8`) en el puerto `53` con un timeout de 1500 ms.
    *   Esto descarta portales cautivos o conexiones Wi-Fi sin salida real a internet.

### 3.3. Sincronización Asíncrona (WorkManager)
*   Se utiliza un `CoroutineWorker` con restricciones de red (`NetworkType.CONNECTED`).
*   **Flujo del Worker**:
    1.  Consulta los registros `PENDING` de la tabla `outbox` ordenados por `timestamp` ascendente.
    2.  Envía las mutaciones al backend a través de la API `/api/v1/sync`.
    3.  **Idempotencia**: Si el servidor devuelve HTTP 200/201, se asume procesado.
    4.  **Confirmación y Purga**: En una transacción de Room, elimina los registros correspondientes de la tabla `outbox`. Si la operación fue `DELETE`, elimina físicamente la orden de la tabla `orders`.
    5.  **Manejo de Errores con Jitter**: Si ocurre un error de red o de servidor recuperable (HTTP 5xx, timeouts), el worker retorna `Result.retry()` configurado con **Retroceso Exponencial con Jitter** para evitar picos de carga. Si el error es de lógica o irrecuperable (HTTP 4xx), la mutación se marca como `FAILED` para revisión o descarte.

---

## 4. Requerimientos de Datos e Idempotencia en Servidor

### Control de Duplicados (Idempotencia)
El Microservicio 1 utilizará una tabla de auditoría en la base de datos PostgreSQL llamada `processed_requests` para evitar procesamientos duplicados:
*   `client_order_id` (PK, UUID)
*   `status` (SUCCESS, PENDING)
*   `processed_at` (Timestamp)

Cualquier petición entrante con un UUID ya existente en esta tabla evitará llamar al Microservicio 2 y responderá éxito directamente al cliente.
