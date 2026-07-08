# Auditoría de Seguridad y Arquitectura: Evasión de Backend (Bypass)

**Fecha de la Auditoría:** 8 de Julio de 2026  

Este documento detalla el análisis de seguridad realizado sobre la aplicación móvil cliente (**UI-HieloPedido**) con el objetivo de identificar la evasión de la capa de microservicios en Java y la manipulación directa de la base de datos a través del SDK de Supabase.

---

## 1. Resumen del Hallazgo

Se ha comprobado que la aplicación móvil **evade completamente la capa de microservicios de Java** (`order-service` / `sync-service`) para todas las transacciones de negocio críticas relativas a perfiles, conductores y pedidos. 

La aplicación móvil realiza consultas e inserciones directas sobre las tablas de base de datos a través del cliente oficial de Supabase (`supabase_flutter`), exponiendo credenciales críticas hardcoded en el frontend y eludiendo las validaciones de negocio del backend.

---

## 2. Detalles Técnicos en el Frontend

### A. Dependencias e Inicialización
La aplicación de Flutter importa directamente la biblioteca de base de datos y se inicializa utilizando credenciales estáticas expuestas en texto plano:
* **Archivo de Configuración:** `lib/core/config/supabase_config.dart`
* **Credenciales Expuestas:**
  * `url`: `https://jnuxrngmqujxkiilrjww.supabase.co`
  * `anonKey`: `eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImpudXhybmdtcXVqeGtpaWxyand3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODAzMzI4MDMsImV4cCI6MjA5NTkwODgwM30.kftJYHymvrPoMksr8F5TRLCPLE1BbCmNdUcskfUdFWw`

### B. Puntos de Bypass Confirmados (Direct Query/Write)
Todas las operaciones ocurren dentro de `lib/providers/order_provider.dart` hacia la API de Supabase sin mediación del backend Java:

1. **Tabla `orders` (Pedidos):**
   * **Bypass de Consulta:** Carga de pedidos filtrada en cliente para Clientes, Repartidores y Administradores.
   * **Bypass de Inserción / Sincronización:** Ejecución directa de operaciones `upsert`, `update` y `delete` para reflejar pedidos y cambios de estado (ej: 'pendiente', 'aceptado', 'en_camino', 'entregado').
2. **Tabla `profiles` (Perfiles y GPS):**
   * **Bypass de Lectura/Escritura:** Consulta y actualización del rol y avatar del usuario.
   * **Bypass de Ubicación GPS:** Reporte periódico de latitud y longitud del repartidor directamente en la tabla remota.
3. **Tabla `repartidor_details` (Calificaciones):**
   * **Bypass de Calificación:** Envío directo de reseñas y estrellas de conductores mediante `update`.

### C. Estado de Integración de Microservicios Java
* **Llamadas a `/api/v1/orders`:** Ninguna.
* **Integración HTTP:** El paquete `http` de Flutter se usa exclusivamente para geolocalización (OSM Nominatim) y enrutamiento vial (OSRM).
* **Parámetros del Servidor Java:** La aplicación móvil carece de cualquier parámetro de URL de backend de Java o configuración de API Gateway.

---

## 3. Riesgos de Negocio e Impacto Técnico

> [!CAUTION]  
> **Riesgo Crítico de Integridad de Datos e Infiltración**
> El bypass detectado anula la principal función de control y seguridad que debe proporcionar la arquitectura de microservicios.

* **Evasión de Reglas de Negocio en Servidor:**
  * El sistema Java no puede validar que los pedidos cumplan con el **peso mínimo de 100 kg** o que respeten las **capacidades máximas de carga** de los vehículos de los repartidores. Un cliente móvil modificado o un script malicioso que use la `anonKey` puede insertar pedidos con cantidades arbitrarias o ficticias (ej: 0.1 kg o cantidades gigantescas) directamente en la base de datos de Supabase.
* **Vulnerabilidad en Control de Acceso (Bypass de RLS):**
  * Al depender de credenciales en código duro, si las Políticas de Seguridad de Fila (Row Level Security - RLS) de Supabase no están correctamente configuradas o se desactivan, cualquier usuario puede acceder o corromper datos de otros clientes, modificar precios o alterar estados de entrega.
* **Silencio de Logs y Auditoría:**
  * Al no pasar por los microservicios Java, los flujos transaccionales no dejan logs de negocio de control interno (IPs, tiempo de respuesta, auditorías comerciales, etc.).
* **Falta de Integraciones Secundarias (Facturación, ERP, Notificaciones):**
  * Dado que la creación de pedidos no pasa por una API intermedia propia, no es posible disparar integraciones sincrónicas o asincrónicas clave (como la emisión de facturas o alertas internas) al momento de confirmarse una venta.

---

## 4. Plan de Remediación para el Equipo de Backend (Java)

Para solventar esta brecha y re-establecer el control sobre la lógica del negocio, se propone la siguiente estrategia de remediación:

```mermaid
sequenceDiagram
    participant App as Cliente Flutter
    participant Gateway as API Gateway (Java)
    participant OrderSvc as order-service (Java)
    participant DB as Base de Datos (Supabase)

    Note over App, DB: Flujo Seguro Propuesto
    App->>Gateway: POST /api/v1/orders (Payload + Token JWT)
    Gateway->>OrderSvc: Validar y Procesar Pedido
    Note over OrderSvc: Verifica Peso Mínimo (>=100 kg)<br/>Valida Capacidad de Carga
    OrderSvc->>DB: Insertar registro de pedido verificado
    DB-->>OrderSvc: Confirmación de guardado
    OrderSvc-->>Gateway: Retorna OrderModel
    Gateway-->>App: 201 Created (Éxito)
```

### Paso 1: Habilitar los Endpoints en el Microservicio de Pedidos
* Exponer formalmente los endpoints REST para la creación y gestión de pedidos bajo `/api/v1/orders` en el microservicio `order-service`.
* Asegurar que el endpoint implemente validaciones estrictas:
  ```java
  if (orderRequest.getQuantity() < 100) {
      throw new BusinessException("El pedido mínimo de hielo debe ser de 100 kg.");
  }
  ```

### Paso 2: Centralizar la Persistencia en el Backend
* Modificar el flujo de persistencia del frontend para que las peticiones se hagan a los endpoints REST de Java.
* El backend Java será el único componente autorizado a escribir en la base de datos de Supabase.

### Paso 3: Endurecer las Row Level Security (RLS) en Supabase
* Configurar políticas RLS restrictivas en Supabase.
* Modificar la base de datos para que las escrituras en la tabla `orders` estén totalmente bloqueadas para el rol `anon`, permitiendo únicamente operaciones a través del rol administrador/servicio de backend (`service_role`) o mediante tokens autenticados validados exclusivamente por el backend Java.

### Paso 4: Monitoreo e Integración Offline
* Para la sincronización de SQLite offline del cliente, en lugar de realizar un `upsert` directo a Supabase, la app móvil debe enviar el lote de mutaciones pendientes a un endpoint de sincronización masiva en Java (ej. `/api/v1/sync`) para que el backend procese, valide e inserte las mutaciones de forma segura.
