# Guía de Integración Frontend-Backend para el Desarrollador

Esta guía técnica explica cómo integrar la aplicación Flutter (**UI-HieloPedido**) con el backend centralizado de microservices en Java (`order-service` y `sync-service`), eliminando el acceso directo a la base de datos Supabase desde el cliente móvil.

---

## 1. Configuración del Entorno de Desarrollo (Levantar el Backend en tu PC)

Para programar y probar la app móvil, el desarrollador frontend **no necesita instalar Docker, PostgreSQL ni configurar bases de datos locales**. Como la base de datos de desarrollo está en Supabase (en la nube), la configuración consiste en:

1.  **Clonar el repositorio del backend:**
    Clonar el proyecto de Java de los microservicios (`ms-hielo`).
2.  **Configurar las credenciales compartidas:**
    Crear un archivo `.env` en la raíz del proyecto backend con los datos de conexión del Supabase compartido:
    ```bash
    SUPABASE_HOST=aws-1-us-east-1.pooler.supabase.com
    SUPABASE_USER=postgres.jnuxrngmqujxkiilrjww
    SUPABASE_PASSWORD=vzeLLKNZy2QOATAG
    
    ORDER_DB_URL=jdbc:postgresql://${SUPABASE_HOST}:6543/order_db?sslmode=require
    ORDER_DB_USER=${SUPABASE_USER}
    ORDER_DB_PASS=${SUPABASE_PASSWORD}
    
    SYNC_DB_URL=jdbc:postgresql://${SUPABASE_HOST}:6543/sync_db?sslmode=require
    SYNC_DB_USER=${SUPABASE_USER}
    SYNC_DB_PASS=${SUPABASE_PASSWORD}
    ```
3.  **Iniciar los servicios:**
    Abrir el proyecto en VS Code y presionar el botón verde de **Play** en la pestaña de depuración para iniciar ambos servicios (`SyncServiceApplication` y `OrderServiceApplication`).
4.  **Conexión desde el Flutter:**
    La aplicación de Flutter local se comunicará con tu servidor local en las siguientes URLs:
    *   **sync-service:** `http://localhost:8080` (para sincronización de outbox y autenticación)
    *   **order-service:** `http://localhost:8082` (para pedidos y catálogo de productos)

---

## 2. Políticas de Seguridad y Autenticación

El frontend **no debe conectarse directamente a la base de datos remota mediante el cliente SQL de Supabase** para leer o escribir tablas. Todo flujo debe pasar obligatoriamente por el backend.

Sin embargo, **Supabase Auth** continuará actuando como el Proveedor de Identidad (IdP). El flujo de autenticación seguro es el siguiente:

1.  La app de Flutter inicia sesión de forma convencional usando Supabase Auth:
    `await Supabase.instance.client.auth.signInWithPassword(...)`
2.  Supabase devuelve un objeto de sesión que contiene un **Access Token (JWT)**.
3.  Para **cada petición HTTP** enviada a la API de Java, el frontend debe adjuntar este token en la cabecera HTTP de autorización:
    ```http
    Authorization: Bearer <TU_JWT_DE_SESION_SUPABASE>
    Content-Type: application/json
    ```
4.  El backend de Java descifra la firma del JWT usando la clave compartida de Supabase y valida de manera segura el rol (`admin`, `distributor`/`repartidor`, `cliente`) y el ID de usuario del solicitante.

---

## 3. Mapeo de Consultas Directas a Endpoints de Java

A continuación se detallan las partes de la UI que se saltan el backend y sus correspondientes endpoints de reemplazo en Java:

| Operación en UI | Archivo / Función actual en Flutter | Consulta Supabase Directa (Reemplazar) | Nuevo Endpoint de Java a Consumir |
| :--- | :--- | :--- | :--- |
| **Carga de Catálogo** | Hardcodeado / Formularios de UI | Ninguno (Estático) | `GET /api/v1/orders/catalog` |
| **Listar Pedidos** | `order_provider.dart` -> `fetchOrdersFromSupabase()` | `.from('orders').select(...)` con filtros en cliente | `GET /api/v1/orders` (Aplica filtros automáticos según rol en backend) |
| **Ver un Pedido** | `order_provider.dart` -> Búsqueda local / remota | `.from('orders').select().eq('client_order_id', ...)` | `GET /api/v1/orders/{orderId}` |
| **Sincronizar Outbox** | `order_provider.dart` -> `syncMutationsToSupabase()` | Bucle manual de `.from('orders').upsert(...)` / `.delete()` | `POST /api/v1/sync` (Envía el lote completo de mutaciones en una sola llamada) |
| **Aceptar Pedido** | Pantalla de repartidores | `.from('orders').update({'repartidor_id': ...})` | `POST /api/v1/orders/{orderId}/accept` |
| **Despachar Pedido** | Flujo de administrador/vendedor | `.from('orders').update({'status': 'despachado'})` | `POST /api/v1/orders/{orderId}/dispatch` |
| **Confirmar Entrega** | Pantalla de reparto + Código OTP + GPS | `.from('orders').update({'status': 'entregado', ...})` | `POST /api/v1/orders/{orderId}/deliver` (Parámetros: `code`, `lat`, `lon` para validar la geocerca de 100m) |
| **Cancelar Pedido** | Flujo de anulación | `.from('orders').update({'status': 'cancelado'})` | `POST /api/v1/orders/{orderId}/cancel` |
| **Enviar Auditoría** | Flujo de conexión/desconexión | `.from('offline_audits').insert(...)` | `POST /api/v1/sync` (como mutación `"CREATE"` con tipo `"AUDIT"`) |

---

## 4. Ejemplos de Refactorización de Código (Dart / Flutter)

### 4.1. Cliente HTTP Base con Interceptor de Token JWT
Se recomienda crear un cliente HTTP centralizado para evitar repetir la inyección del token:

```dart
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:supabase_flutter/supabase_flutter.dart';

class ApiClient {
  static const String baseUrl = 'https://tu-gateway-api-domain.com'; // O IP local de desarrollo: http://10.0.2.2:8082

  static Future<Map<String, String>> _headers() async {
    final session = Supabase.instance.client.auth.currentSession;
    final token = session?.accessToken ?? '';
    return {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer $token',
    };
  }

  static Future<http.Response> get(String path) async {
    final url = Uri.parse('$baseUrl$path');
    return http.get(url, headers: await _headers());
  }

  static Future<http.Response> post(String path, dynamic body) async {
    final url = Uri.parse('$baseUrl$path');
    return http.post(url, headers: await _headers(), body: jsonEncode(body));
  }
}
```

---

### 4.2. Refactorización de la Sincronización del Outbox (Outbox Sync)
En lugar del bucle manual que consume múltiples escrituras directas sobre Supabase, debe enviarse el lote completo de mutaciones al servicio de sincronización de Java en una sola transacción HTTP:

#### Código a Reemplazar (`order_provider.dart`):
```dart
// ANTES (Bypass directo de Supabase):
if (operation == 'CREATE' || operation == 'UPDATE') {
    final Map<String, dynamic> orderMap = jsonDecode(payloadStr);
    await supabaseClient.from('orders').upsert(orderMap);
}
```

#### Nuevo Código Refactorizado:
```dart
// DESPUÉS (Consumiendo el sync-service estructurado):
Future<void> syncMutationsToBackend() async {
  if (currentUser == null || !_isOnline) return;

  final pendingMutations = await _db.getPendingMutations();
  if (pendingMutations.isEmpty) return;

  _isSyncing = true;
  notifyListeners();

  try {
    // 1. Mapear mutaciones locales al formato Dto del backend Java
    final List<Map<String, dynamic>> body = pendingMutations.map((m) {
      return {
        'id': m['id'],
        'entityType': m['entity_type'],
        'entityId': m['entity_id'],
        'operation': m['operation'],
        'payload': m['payload'],
        'timestamp': m['timestamp'],
      };
    }).toList();

    // 2. Enviar el lote completo en un solo POST
    final response = await ApiClient.post('/api/v1/sync', body);

    if (response.statusCode == 200) {
      final responseData = jsonDecode(response.body);
      final List<dynamic> processedIds = responseData['processedIds'];

      if (processedIds.isNotEmpty) {
        // 3. Purgar del outbox SQLite solo las mutaciones procesadas con éxito
        await _db.deleteMutations(List<String>.from(processedIds));
        await _db.markOrdersAsSynced(List<String>.from(processedIds));
        await loadOrders();
      }
    } else {
      debugPrint('Sync failed with status code: ${response.statusCode}');
    }
  } catch (e) {
    debugPrint('Error syncing: $e');
  } finally {
    _isSyncing = false;
    notifyListeners();
  }
}
```

---

### 4.3. Refactorización de la Carga de Pedidos (fetchOrders)

#### Código a Reemplazar (`order_provider.dart`):
```dart
// ANTES (Carga directa desde la tabla remota orders):
response = await supabaseClient
    .from('orders')
    .select('*, profiles!user_id(avatar_url)')
    .eq('user_id', currentUser!.id);
```

#### Nuevo Código Refactorizado:
```dart
// DESPUÉS (Consumiendo el listado inteligente del backend de Java):
Future<void> fetchOrdersFromBackend() async {
  if (currentUser == null || !_isOnline) return;
  _isLoading = true;
  notifyListeners();

  try {
    // La API GET /api/v1/orders de Java detecta el rol del JWT 
    // y filtra los pedidos automáticamente en la consulta del servidor
    final response = await ApiClient.get('/api/v1/orders');

    if (response.statusCode == 200) {
      final List<dynamic> data = jsonDecode(response.body);
      final fetchedOrders = data
          .map((o) => OrderModel.fromMap(o as Map<String, dynamic>))
          .toList();

      await _db.cacheOrders(fetchedOrders);
      // ... resto de lógica local de mezcla outbox ...
    }
  } catch (e) {
    debugPrint('Error fetching orders: $e');
  } finally {
    _isLoading = false;
    notifyListeners();
  }
}
```
