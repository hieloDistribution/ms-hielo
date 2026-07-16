# Propuesta — admin-console

> Cambio SDD para el proyecto `hielo` (backend en `~/projects/ms-hielo`, cliente Flutter en `~/projects/UI-HieloPedido`).
> Phase: `sdd-proposal`. Fechado tras el cierre del preflight y la ronda de preguntas de producto.

## Status

`ready-for-spec`. Decisiones de producto cerradas; no quedan preguntas abiertas críticas.

## 1. Problema

El operador de la distribuidora (una sola empresa) necesita administrar quién hace qué dentro de la app. Hoy:

- La pantalla `AdminScreen` existe pero **solo lee** (métricas, despachos, auditoría online). No lista usuarios, no crea, no asigna roles, no suspende.
- El endpoint `POST /api/v1/auth/signup` **acepta un `role` enviado por el cliente**. Combinado con el `RegisterScreen` que le permite al usuario elegir "Preventista" en un formulario abierto, **cualquier persona se auto-asigna rol `repartidor` sin aprobación**.
- En el backend no hay un `AdminContext` ni filtros por rol (`SecurityConfig` solo pide autenticación). Cualquier usuario autenticado puede llamar a cualquier endpoint.
- **No hay forma documentada de crear al primer admin**. El sistema nunca tuvo un administrador propio; la pantalla "admin" la usa quien fuera con cualquier cuenta creada por signup.

Esto bloquea decisiones de negocio básicas (asignar repartidores, suspender usuarios conflictivos, promover un back-office a admin) sin apoyarse en una consola seria y sin darle control al dueño de la empresa.

## 2. Outcome de negocio esperado

Al cierre de este cambio (primer slice):

- Existe **al menos un admin real** en el sistema, creado por una sola vía operativa y registrada en bitácora.
- Un admin puede desde la propia app: listar usuarios, asignar roles (`admin`, `repartidor`, `cliente`), invitar a otros admins, desactivar usuarios y ver un audit log de sus acciones administrativas.
- **Nadie puede auto-asignarse un rol sensible** (repartidor o admin). El signup público solo crea cuentas `cliente`; las promociones las hace un admin.
- El `AdminScreen` actual deja de ser un panel de métricas disfrazado y pasa a ser una consola con al menos dos vistas: **Usuarios** (lista + acciones) e **Invitaciones** (crear y revocar). Los tabs de métricas se mantienen pero se separan de las acciones administrativas.

## 3. Usuarios objetivo y situaciones

- **Operador / dueño de la distribuidora** (1 persona, tal vez 2). Es el primer admin creado por el seeder CLI. Hace onboarding de los repartidores desde la oficina. Necesita que la creación de admins adicionales sea segura y registre.
- **Back-office / oficinista administrativo** (0-5 personas). Admins secundarios para分担 la carga. Mismas facultades que el dueño sobre identidad y roles; no tocan inventario ni precios en este slice.
- **Repartidor / preventista** (5-30 personas). Rol promovido por un admin. Necesita saber que ya no se puede auto-registrar y que la flota la maneja la oficina.
- **Cliente final** (negocios que reciben pedidos). Caso borde: un cliente que ya tenía cuenta `preventista` autogestionada queda migrado a `cliente` o promovido a `preventista` por un admin según el caso; el sistema no decide automáticamente.

## 4. Primer slice — Alcance

### 4.1 Dentro (in-scope)

- **Backend `sync-service`**:
  - Seeder CLI one-shot al primer arranque, si y solo si no existe ningún `ADMIN`. Imprime email + contraseña random a la consola, marca `must_change_password=true`.
  - Endpoint admin-gated `POST /api/v1/admin/invites` que emite un invite token HMAC-SHA256 (`{email, role, exp, jti}`) con expiración 24h y un solo uso.
  - Endpoint `POST /api/v1/admin/invites/redeem` (con el token en el link) que crea al usuario y le pide setear contraseña.
  - Endpoints admin-gated:
    - `GET  /api/v1/admin/users?role=&q=` (lista paginada con búsqueda).
    - `GET  /api/v1/admin/users/{id}` (detalle).
    - `PATCH /api/v1/admin/users/{id}/roles` (asignar/quitar roles).
    - `POST /api/v1/admin/users/{id}/deactivate` (soft delete).
    - `POST /api/v1/admin/users/{id}/reactivate`.
    - `GET  /api/v1/admin/audit-log` (bitácora paginada).
  - Cierre del bypass: `POST /api/v1/auth/signup` deja de aceptar `role`. Crea siempre `users.role='CLIENT'`. Cualquier intento de enviar `role` distinto se ignora silenciosamente y se loggea como evento de seguridad.
- **Backend `order-service`**:
  - `RoleAuthorizationFilter` que decodifica el claim `roles` (lista) y expone un `AdminContext` con `hasRole(...)`.
  - Aplicación del gate a `/api/v1/admin/**` (vía `@PreAuthorize("hasRole('ADMIN')")` o equivalente en filter chain). Se aplica desde `sync-service` también para los endpoints que viven ahí.
- **Base de datos**:
  - Migración que agrega `users.active BOOLEAN NOT NULL DEFAULT true`, `users.must_change_password BOOLEAN NOT NULL DEFAULT false`, y crea las tablas `admin_invites(id, email, role, token_hash, expires_at, used_at, created_by, created_at)` y `admin_audit_log(id, actor_user_id, action, target_user_id, before_json, after_json, request_id, created_at)`.
  - Migración que convierte la columna `users.role` a `users.roles TEXT[]` (Postgres `text[]`) para soportar multi-rol real. Si la migración es destructiva, doble-escritura durante un periodo de transición con backward-compat en `JwtService` (parse tanto string único como array).
- **Frontend Flutter (`UI-HieloPedido`)**:
  - Reescribir `AdminScreen` con dos secciones reales: **Usuarios** (DataTable o ListView con buscador, chips de filtro por rol, botones de acción) e **Invitaciones** (form + listado de tokens pendientes con revocar). Mantener los tabs actuales de métricas en una sección secundaria llamada "Operaciones".
  - Quitar del `RegisterScreen` la opción visible de autoelegir `Preventista` o `Cliente`. La pantalla queda como auto-registro de cliente; el resto es por admin.
  - Decodificar el nuevo claim `roles` desde el profile snapshot; ajustar el gating del `main_navigation_screen.dart` para que use un set de roles en vez de un solo string.
- **Documentación**:
  - Actualizar `docs/SECURITY_AUDIT.md` para reflejar el estado post-cambio (lo que queda abierto fuera de scope como Issue de seguimiento).
  - Cómo-operar para el seeder CLI (Runbook en `docs/RUNBOOK_ADMIN_BOOTSTRAP.md`).

### 4.2 Fuera (out-of-scope, primer slice)

- Onboarding de repartidores con validación de matrícula/vehículo por OCR o formulario. Hoy el admin promueve y el repartidor completa datos en su perfil.
- Asignación de rutas, zonas, optimización de carga. Es otra historia.
- Auditoría offline (chips de "online/offline" en ruta, last_seen, signal). Se mantiene el panel actual; refactor a datos reales del backend es una propuesta separada.
- Inventario de productos, precios, descuentos. Otra historia.
- SSO / OIDC / magic links. Queda como decisión explícita de no hacer en este slice por costo operacional.
- Hard-delete de usuarios. Solo soft-delete + reactivación.
- Invitaciones offline (sin red en oficina). Por ahora se requiere conexión.

## 5. Reglas de negocio

1. **Auto-registro**: un usuario anónimo que complete el flujo público siempre queda como `cliente`. El campo `role` enviado por el cliente se ignora silenciosamente.
2. **Bootstrap del primer admin**: solo puede existir si `count(users WHERE roles @> ARRAY['ADMIN'] AND active=true) == 0` al momento de levantar el `sync-service`. Es idempotente — múltiples boots no crean duplicados; un solo admin queda.
3. **Credenciales bootstrap**: la contraseña random se imprime a stdout una única vez. No se loggea, no se persiste en claro, no se devuelve por API. `must_change_password=true` obliga al usuario a cambiarla en el primer login.
4. **Invite admin**: solo `admin` activos pueden emitir invites. El token es single-use y expira a las 24h. Si un admin intenta emitir un invite con `role='ADMIN'`, el sistema pide confirmación (rol privilegiado).
5. **Asignar roles**: solo `admin` activos pueden cambiar roles. Un admin no puede quitarse a sí mismo el rol `ADMIN` si es el único admin activo (protección contra auto-lock-out).
6. **Soft delete**: desactivar un usuario lo deja:
   - Con `active=false`, removido de listados activos.
   - Con todas sus `refresh_tokens` revocadas (fuerza re-login fallido en cualquier dispositivo).
   - Visible en listados con badge "desactivado" + filtro dedicado.
   - Con la operación registrada en `admin_audit_log`.
7. **Reactivación**: solo un admin activo puede reactivar; equivale a un soft-delete inverso más una entrada nueva en `admin_audit_log`.
8. **Bitácora**: cada acción admin (crear invite, redeem invite, cambiar rol, desactivar, reactivar) deja una fila en `admin_audit_log` con `actor_user_id`, `action`, `target_user_id`, snapshot antes/después, `request_id` y timestamp.
9. **Multi-rol**: un usuario puede tener varios roles (`ADMIN + CLIENT`, por ejemplo) si un admin lo decide. Por defecto, nadie arranca con multi-rol.
10. **Thievery detection**: la matriz de refresh-token rotation + burn se mantiene intacta. Si un usuario desactivado intenta hacer refresh, su familia entera se quema como antes.

## 6. Edge cases a resolver durante design

1. **Lost admin**: si todos los admins quedan desactivados, nadie puede promover a nadie. Mitigación: el seeder CLI puede correr manualmente (`java -jar ... --admin-recover=email@x`) siempre que la operación esté autenticada por presencia física. Alternativa más simple: tener un `--admin-recover-email` env opcional cargado al boot que solo actúa si `admin_count==0`. Decisión se toma en `sdd-design`.
2. **Race-promote**: dos admins promueven al mismo usuario a roles distintos en paralelo. Mitigación: las mutaciones de roles usan `UPDATE ... WHERE version=X` (optimistic lock) y devuelven 409 si la versión cambió. Alternativa más simple: lock pesimista por fila. Decisión en design.
3. **Repartidor en ruta al ser promovido/desactivado**: si está conectado vía WebSocket y se le quita `role=REPARTIDOR`, el backend corta su sesión (revocar refresh tokens + cerrar ws). Detalle de implementación en design.
4. **Invitación expirada vs. usada vs. pendiente**: tres estados finitos; el redeem solo funciona si el token está `pending` Y no expirado. UI debe distinguir.
5. **Self-demote del último admin**: bloqueado por regla #5.
6. **Self-edit del propio admin**: un admin puede editar sus propios campos no sensibles (avatar_url, full_name). NO puede editar su email ni su rol por la misma ruta (debe hacerlo otro admin). Decisión final en design.
7. **Migración de cuentas existentes**: hoy todos los usuarios tienen un único rol en `users.role` (string). La migración a `roles TEXT[]` debe tratar a cada fila con su rol histórico como `roles=[role_viejo]`. Los `preventista` autogestionados pueden ser promovidos por admins a `repartidor` o degradados a `cliente` según auditoría.

## 7. Trade-offs y discusiones de producto

| Decisión | Opción A | Opción B | Elegida | Por qué |
| --- | --- | --- | --- | --- |
| Bootstrap admin | CLI seeder one-shot | env vars / SQL seed / OIDC | A | El operador único retiene custodia del primer credential sin filtrarlo por repo ni logs. |
| Invitaciones admin | Token HMAC firmado | Magic link con email | Token HMAC | Evita meter un provider de email al stack; el operador comparte el link por WhatsApp. |
| Multi-rol en JWT | `roles TEXT[]` claim | claim único `role` actualizado | A | Coexistencia natural admin/cliente/repartidor sin trampas de parsing. |
| Self-demote | Bloqueado si es único admin | Permitido siempre | Bloqueado | Previene lockout humano. |
| Audit storage | Tabla en Postgres | Append-only log externo | Tabla en Postgres | Suficiente para single-tenant; append-only via trigger `REVOKE UPDATE,DELETE`. |
| Desactivación | Soft delete + audit | Hard delete físico | Soft delete + audit | Trazabilidad comercial y de seguridad. |

## 8. Implicaciones

- **Seguridad**: este cambio cierra el bypass real (signup que aceptaba role), introduce el primer admin real, y deja lista la mesa para revocar tokens en cadena (igual que las familias de refresh-token). Lo que sigue abierto fuera de scope: endurecimiento de CORS, rate limiting, login lockout policy. Estos van a una propuesta posterior.
- **Operación**: el dueño tiene que estar presente en el primer boot del `sync-service` para capturar las credenciales. Se documenta en runbook.
- **Base de datos**: migración aditiva (no rompe datos existentes). Doble-parse del claim `role` durante una ventana corta.
- **Frontend**: baja del lado de `RegisterScreen` (más simple) y nueva sección en `AdminScreen`. El resto de la app queda igual.
- **Testing**: bajo strict TDD. El primer RED es "POST /api/v1/auth/signup con role=ADMIN devuelve 201 y crea admin" → debe fallar inmediatamente porque removemos el campo. Esa es la primera prueba.

## 9. Non-goals explícitos

- Multi-tenant. Esta distribución es una sola empresa.
- IdP externo (Google Workspace, Auth0, Keycloak). Decidido por costo operativo.
- Permisos granulares por admin (RBAC fino). Cualquier `ADMIN` puede hacer todo lo que un admin hace. Si más adelante hace falta, se modela con claims adicionales sin romper este slice.
- Notificaciones por email (transaccional). El invite se comparte por WhatsApp/email manual; el backend no envía correos.
- Hard-delete de usuarios. Soft-delete y auditoría son la fuente de verdad.
- Sincronización de la UI admin offline. La consola admin requiere conexión a internet.

## 10. Criterios de aceptación del primer slice

1. **FIRST_ADMIN_BOOTSTRAP**: con la base de datos vacía, levantar el `sync-service` produce un único admin con credenciales impresas a stdout; los boots subsiguientes no duplican.
2. **SIGNUP_BYPATCH_CLOSED**: `POST /api/v1/auth/signup` con cualquier `role` (admin, repartidor, cliente) crea un usuario con `roles=['CLIENT']` y registra el intento en `admin_audit_log` con `action='signup_role_ignored'`.
3. **ADMIN_INVITE_FLOW**: un admin crea un invite, lo canjea el invitado, queda con el rol solicitado y arranca con `must_change_password=true`.
4. **ROLE_GATE**: cada endpoint de `/api/v1/admin/**` rechaza (HTTP 403) si el JWT del caller no contiene `roles` conteniendo `'ADMIN'`.
5. **LIST_USERS**: la consola admin lista usuarios con filtro por rol y búsqueda por nombre/email. Devuelve usuarios desactivados con badge, no los oculta.
6. **DEACTIVATE_USER**: un admin puede desactivar; el usuario desactivado no puede renovar tokens; la operación queda en `admin_audit_log`.
7. **AUDIT_LOG_VIEW**: la consola admin muestra las últimas N acciones con actor, target, acción, timestamp.
8. **SELF_DEMOTE_BLOCKED**: si hay un único admin activo, el endpoint para quitarse `ADMIN` devuelve 409 con código `cannot_self_demote_last_admin`.
9. **REGISTER_SCREEN_PUBLIC**: el `RegisterScreen` queda solo para auto-registro de clientes; no aparece la opción "Preventista".
10. **RUNBOOK**: existe `docs/RUNBOOK_ADMIN_BOOTSTRAP.md` con el procedimiento exacto para el primer boot y para `--admin-recover`.

## 11. Riesgos abiertos para design

- Decisión de "lost admin recovery": CLI vs env vs intervención manual. Se cierra en `sdd-design`.
- Locking strategy en mutaciones de roles: optimistic vs pessimistic. Decisión en `sdd-design`.
- Cut-over de doble-escritura del claim `role` → `roles`. Duración de la ventana de compatibilidad. Decisión en `sdd-design`.
- Política de rate limiting para `/api/v1/auth/invites/redeem`. Decisión en `sdd-design` (probablemente basada en count de intentos por IP por minuto).

---

## Decisiones cerradas en esta proposal

| Tema | Decisión |
| --- | --- |
| Bootstrap admin | Opción A: CLI seeder one-shot + invite tokens HMAC |
| Alcance primer slice | Solo identidad y roles |
| Desactivación de usuarios | Soft delete + `admin_audit_log` |
| Cantidad de admins | Múltiples desde el día 1 |
| Claim | Doble-escritura transitoria `role` (string) → `roles` (array); backward-compat en `JwtService` durante la ventana de migración |
| Audit storage | Tabla `admin_audit_log` con `REVOKE UPDATE, DELETE` vía migración |
| Out of scope | Catálogo, ruteo, auditoría geo, IdP, magic link, hard delete, offline admin |

## Next recommended phase

`sdd-spec` — redactar las delta-specs `specs/admin-roles/spec.md` y `specs/admin-bootstrap/spec.md` referenciando `openspec/specs/auth/spec.md` por nombres de heading. Aplicar strict TDD en `sdd-apply`.
