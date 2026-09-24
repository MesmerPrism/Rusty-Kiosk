# BLE phone control

A browser phone page can request bounded actions from a Quest app over BLE.
Treat this as a transport and interaction example: a purpose-built app supplies
its own command vocabulary and effects. Do not move application-specific
session, Arm, Save, or experiment rules into a shared controller.

## Integration record

- **Roles and permissions:** name the phone/browser central and Quest GATT
  peripheral roles, services and characteristics, supported browsers, and the
  Android permissions actually used by the selected roles. Scanning,
  advertising, and connecting are separate capabilities.
- **Browser lifecycle:** use a secure context and user-mediated device
  selection where Web Bluetooth requires them. Serialize GATT operations;
  rediscover services and characteristics after disconnect. Track reconnect
  generations and remove stale event listeners.
- **Authority:** browser device permission, application pairing, command
  admission, and effect confirmation are separate steps. If a shared command
  authority is selected, use the Manifold owner contract. Keep pairing secrets
  and application policy in their owning app.
- **Receipts:** bind request identity and connection generation, distinguish
  write/notification delivery from accepted command and observed effect, and
  make timeout or panel closure explicit. A missing receipt leaves the result
  unresolved even if a later state observation suggests an effect occurred.

Quest owns reusable Android BLE/GATT mechanics once an independent consumer or
neutral harness supports extraction. The phone page may remain an app-owned
asset when it carries that app's operator semantics. Rusty Kiosk merely indexes
the composition; it does not gain BLE permissions or controller authority.

## Host-only checks before device work

Validate protocol schemas and bounds, reject unknown commands, and test
duplicate, stale-generation, disconnect, timeout, and permission-denied paths
with fake peers. Check the Android manifest and browser assets for only the
declared capabilities and origins. Browser behavior, radio reconnect, wearer
consent, and effective app changes remain device/browser validation questions.
