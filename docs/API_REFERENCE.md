# Referencia de la API REST

Este documento detalla los endpoints expuestos por los microservicios backend del sistema **HieloPedido**.

---

## 1. Endpoints de Autenticación (sync-service - Puerto 8080)

Todos los endpoints bajo `/api/v1/auth` son públicos (`permitAll`).

### A. Inicio de Sesión
*   **Método / Ruta**: `POST /api/v1/auth/login`
*   **Cuerpo de Petición (JSON)**:
    ```json
    {
      "username": "vendedor1",
      "password": "mi_password_seguro"
    }
    ```
*   **Respuestas**:
    *   **`200 OK`**: Login exitoso. Retorna los tokens y el identificador de preventista (vendor_id).
        ```json
        {
          "access_token": "eyJhbGciOiJIUzI1NiJ9...",
          "refresh_token": "eyJhbGciOiJIUzI1NiJ9...",
          "vendor_id": "8b9e6f8a-cd62-4b6b-871d-551152a5a5ad"
        }
        ```
    *   **`400 Bad Request`**: Solicitud con formato o parámetros inválidos.
        ```json
        {
          "error": "invalid_request"
        }
        ```
    *   **`401 Unauthorized`**: Contraseña o usuario incorrectos.
        ```json
        {
          "error": "invalid_credentials"
        }
        ```
    *   **`423 Locked`**: La cuenta del usuario se encuentra bloqueada administrativamente.
        ```json
        {
          "error": "account_locked"
        }
        ```

### B. Rotación de Token de Refresco
*   **Método / Ruta**: `POST /api/v1/auth/refresh`
*   **Cuerpo de Petición (JSON)**:
    ```json
    {
      "refresh_token": "eyJhbGciOiJIUzI1NiJ9..."
    }
    ```
*   **Respuestas**:
    *   **`200 OK`**: Rotación exitosa. Retorna un nuevo par de tokens.
        ```json
        {
          "access_token": "eyJhbGciOiJIUzI1NiJ9.new...",
          "refresh_token": "eyJhbGciOiJIUzI1NiJ9.new...",
          "vendor_id": "8b9e6f8a-cd62-4b6b-871d-551152a5a5ad"
        }
        ```
    *   **`401 Unauthorized`**: El token expiró o ya fue previamente utilizado (lo cual activa la revocación de la familia).
        ```json
        {
          "error": "token_expired"
        }
        // o bien:
        {
          "error": "token_revoked"
        }
        ```

---

## 2. Endpoints de Sincronización (sync-service - Puerto 8080)

Estos endpoints requieren autenticación mediante JWT pasándolo en el header `Authorization: Bearer <access_token>`.

### A. Enviar Lote de Sincronización
*   **Método / Ruta**: `POST /api/v1/sync`
*   **Cabeceras**:
    *   `Authorization`: `Bearer <access_token>`
    *   `Content-Type`: `application/json`
*   **Cuerpo de Petición (JSON)**: Un array con mutaciones ordenadas:
    ```json
    [
      {
        "id": "mutation-uuid-1",
        "entityType": "ORDER",
        "entityId": "order-uuid-abc",
        "operation": "CREATE",
        "payload": "{\"clientOrderId\":\"order-uuid-abc\",\"clientId\":\"Nombre Cliente\",\"salespersonId\":\"VENDEDOR-001\",\"createdAt\":\"2026-07-02T12:00:00\",\"totalAmount\":250.0,\"items\":[{\"productId\":\"PROD-ICE-002\",\"quantity\":1,\"price\":250.0}]}",
        "timestamp": 1785590000000
      }
    ]
    ```
*   **Respuestas**:
    *   **`200 OK`**: Proceso de lote completado. Retorna la lista de IDs que se guardaron con éxito.
        ```json
        {
          "success": true,
          "processedMutationIds": [
            "mutation-uuid-1"
          ],
          "errorMessage": null
        }
        ```
    *   **`401 Unauthorized`**: Token de acceso ausente, inválido o expirado.
        ```json
        {
          "error": "token_expired"
        }
        ```
    *   **`500 Internal Server Error`**: Ocurrió un error al procesar alguna de las mutaciones intermedias. Retorna los IDs consolidados exitosamente antes del fallo para permitir que el cliente los purgue y no repita esas operaciones en el reintento.
        ```json
        {
          "success": false,
          "processedMutationIds": [],
          "errorMessage": "Error en mutacion mutation-uuid-1: Stock insuficiente para el producto..."
        }
        ```

### B. Obtener Estado de una Mutación (Idempotencia)
*   **Método / Ruta**: `GET /api/v1/sync/status/{clientRequestId}`
*   **Respuestas**:
    *   **`200 OK`**: Retorna el registro de auditoría del backend.
        ```json
        {
          "clientRequestId": "mutation-uuid-1",
          "status": "SUCCESS",
          "processedAt": "2026-07-03T12:00:00"
        }
        ```
    *   **`404 Not Found`**: El ID especificado no ha sido enviado al servidor o no ha empezado a procesarse.

---

## 3. Endpoints de Pedidos (order-service - Puerto 8081)

Estos endpoints son internos y expuestos para ser consumidos por el `sync-service` (o por el cliente si hay comunicación directa). Requieren la cabecera `Authorization: Bearer <access_token>`.

### A. Crear o Actualizar un Pedido
*   **Método / Ruta**: `POST /api/v1/orders`
*   **Cuerpo de Petición (JSON)**:
    ```json
    {
      "clientOrderId": "order-uuid-abc",
      "clientId": "Nombre Cliente",
      "salespersonId": "8b9e6f8a-cd62-4b6b-871d-551152a5a5ad",
      "createdAt": "2026-07-02T12:00:00",
      "totalAmount": 250.00,
      "items": [
        {
          "productId": "PROD-ICE-002",
          "quantity": 1,
          "price": 250.00
        }
      ]
    }
    ```
*   **Respuestas**:
    *   **`201 Created`**: Pedido guardado y stock descontado exitosamente. Retorna la entidad guardada en PostgreSQL.
    *   **`400 Bad Request`**: Datos inválidos, producto inexistente, stock disponible insuficiente, o el peso del pedido no cumple con las reglas del negocio (peso mínimo de 100 kg o exceso del límite de 5.000 kg por camión/ruta de entrega).
    *   **`403 Forbidden`**: El token no posee un preventista asociado (`vendor_id` nulo) o el `salespersonId` enviado en la orden difiere del `vendor_id` del token JWT.
        *   Cuerpo: `"No está autorizado a registrar o modificar pedidos para el distribuidor: ..."`

### B. Obtener un Pedido
*   **Método / Ruta**: `GET /api/v1/orders/{orderId}`
*   **Respuestas**:
    *   **`200 OK`**: Retorna el pedido detallado.
        ```json
        {
          "clientOrderId": "order-uuid-abc",
          "clientId": "Nombre Cliente",
          "salespersonId": "8b9e6f8a-cd62-4b6b-871d-551152a5a5ad",
          "createdAt": "2026-07-02T12:00:00",
          "totalAmount": 250.00,
          "items": [...]
        }
        ```
    *   **`403 Forbidden`**: El pedido solicitado pertenece a otro preventista (`salespersonId` diferente al `vendor_id` del token) o el token no tiene preventista asignado.
        *   Cuerpo: `"No está autorizado a consultar pedidos de otros vendedores."`
    *   **`404 Not Found`**: No existe ningún pedido con el ID provisto.

### C. Eliminar un Pedido (y Revertir Stock)
*   **Método / Ruta**: `DELETE /api/v1/orders/{orderId}`
*   **Respuestas**:
    *   **`200 OK`**: El pedido se eliminó físicamente de PostgreSQL y el stock de los productos se incrementó de vuelta según las cantidades originales del pedido.
    *   **`400 Bad Request`**: No se encontró ningún pedido con el ID provisto.
    *   **`403 Forbidden`**: El token no posee un preventista asociado o el pedido solicitado pertenece a otro preventista (discrepancia de ID).
        *   Cuerpo: `"No está autorizado a eliminar pedidos de otros vendedores."`

### D. Descargar Catálogo de Productos
*   **Método / Ruta**: `GET /api/v1/orders/catalog`
*   **Respuestas**:
    *   **`200 OK`**: Retorna el catálogo oficial para sincronización en caché local del preventista.
        ```json
        [
          {
            "id": "PROD-ICE-001",
            "name": "Bolsa Hielo Cubos 2kg",
            "price": 120.00,
            "stock": 500,
            "weightKg": 2.0
          },
          {
            "id": "PROD-ICE-002",
            "name": "Bolsa Hielo Cubos 5kg",
            "price": 250.00,
            "stock": 300,
            "weightKg": 5.0
          }
        ]
        ```
