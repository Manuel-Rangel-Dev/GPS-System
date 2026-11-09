# Recorridos Kia K3 (versión 1.2)

El selector conserva Vehículo 2 por defecto. Este mantiene el envío de ubicación
cada cinco segundos con `vid=celular`. Kia K3 inicia un recorrido explícito OBD;
ambos servicios se excluyen, el selector se bloquea durante un envío manual,
automático o recorrido, y Planificar ruta está disponible en los dos vehículos.
BootReceiver solo reanuda Vehículo 2. El recorrido OBD no se restaura después de
morir el proceso ni de reiniciar el dispositivo.

## Protocolo y datos

`obd/ClienteElm.kt` trabaja con streams o Socket sin Android. Respeta la secuencia
de inicialización y los tiempos del firmware. Lee hasta `>`, drena antes de cada
comando y acepta eco, espacios y respuestas fragmentadas. `PidsObd.kt` contiene
los quince canales y las ecuaciones. Cada ciclo lee los siete rápidos y un lento
en turno; los demás lentos conservan su última lectura. Un canal leído sin
respuesta queda null. Se intenta un ciclo por segundo; los timeouts pueden
alargarlo.

Tres fallos consecutivos reconectan TCP. En modo emulador se permite completar
los FAST cuando responden NO DATA para distinguir el fin de ruta: tres filas
con todos los rápidos en NO DATA dejan el recorrido abierto, muestran el aviso
y no agregan filas vacías. Esta comprobación tiene prioridad sobre la reconexión
por esos NO DATA; otros errores siguen provocando reconexión.

Cada fila conserva su UTC y posición al cerrarse; `t_ms` corresponde al inicio de
su ciclo, relativo al inicio del recorrido. Los fixes reales usan antigüedad
monótona menor de cinco segundos. En emulador se utiliza exclusivamente ATGPS.
El RID usa hora de Bogotá y prefijo kia_/emu_; UTC se calcula con el reloj del
celular. Las funciones de fecha están compartidas en `Tiempo.kt`.

`PaqueteObd.kt` construye JSON sin org.json, escapa strings, normaliza valores
no finitos a null y exige menos de 600 bytes. `OBD_TOKEN` está únicamente en
LocationData.kt y vacío omite tok. Se reutiliza UDP_DESTINATIONS. UDP no tiene
confirmación del servidor: enviada significa que al menos un send local funcionó,
después de intentar todos los destinos.

La cola sincronizada almacena hasta 400 filas y descarta la más antigua al llenarse.
La confirmación identifica la fila original para evitar retirar otra si hubo un
desborde durante el envío. Al finalizar se pierde la cola en memoria; si quedan
filas, la interfaz pide confirmar el descarte.

## Redes y permisos

- API 29+: requestNetwork + WifiNetworkSpecifier, sin capacidad INTERNET, con
  SSID exacto y WPA2 opcional. Android presenta su diálogo de conexión.
- API 24–28: la app indica conectarse manualmente al SSID elegido en Ajustes y
  conservar la Wi-Fi sin internet. Busca las redes existentes y las futuras con
  NetworkCallback, verificando el SSID de WifiManager. No intenta configurar la
  Wi-Fi con las APIs antiguas.
- El TCP se crea con la socketFactory de esa Network. Para emulador, el host sale
  de la ruta IPv4 por defecto de LinkProperties; si falta, de DhcpInfo.gateway o
  serverAddress. El ELM real usa 192.168.0.10:35000.
- Se solicita celular con INTERNET y se ata cada DatagramSocket a esa Network.
  Como respaldo solo se acepta la red predeterminada con internet validado y que
  no sea la Network OBD actual ni la última conocida. Nunca se usa
  bindProcessToNetwork. Sin red de envío, las filas permanecen en cola.
- onLost invalida la red; cambiar o perder Wi-Fi cierra el TCP. Se liberan ambos
  callbacks al finalizar, incluyendo la protección contra callbacks tardíos.
- Se mantiene ubicación precisa también en emulador por el servicio de tipo
  location. No se usan APIs que requieran NEARBY_WIFI_DEVICES. Como el proyecto
  apunta a SDK 37, se declara y solicita ACCESS_LOCAL_NETWORK en Android 17 al
  iniciar Kia K3 (https://developer.android.com/privacy-and-security/local-network-permission).
- El recorrido mantiene un wake lock parcial y reutiliza la solicitud de exclusión
  de batería existente. El estado y la notificación se actualizan aproximadamente
  cada segundo.

## Pruebas y validación

Pruebas JVM en `app/src/test/java/com/uninorte/locator/ObdTest.kt`: los quince
vectores del contrato, eco/búsqueda/errores, ATGPS, JSON, RID, fechas, consumo,
cola, streams fragmentados e integración con servidor TCP falso. Si SIM_PUERTO
está definido se conecta a 127.0.0.1 y lee cinco ciclos; en otro caso usa Assume.

Comandos habituales (Java 25, SDK de local.properties):

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
SIM_PUERTO=35000 ./gradlew testDebugUnitTest --rerun-tasks
```

En esta sesión el sandbox impidió iniciar Gradle: inicialmente no dejó escribir
el lock en ~/.gradle; con la caché copiada a /tmp el error fue:
`Could not determine a usable wildcard IP for this machine.`
No se llegó a resolver dependencias por red ni a producir un APK nuevo con Gradle.

Como comprobación alternativa se compiló todo el Kotlin contra el SDK y las
dependencias locales usando el compilador Kotlin 2.2.10, y se compilaron y enlazaron
los recursos con AAPT2 (incluido el R real generado). Esto comprueba tipos y
recursos, pero no sustituye assembleDebug ni su empaquetado.

Ejecución directa con JUnit: ocho pruebas pasan, SIM_PUERTO se omite y la prueba
TCP falla al crear ServerSocket con `java.net.SocketException: Operation not
permitted`, una restricción del sandbox. No se altera esa prueba para ocultarlo.

No hay emulador Android ni celular conectado: quedan pendientes la revisión visual,
los diálogos/permisos, el ELM327 físico, el hotspot emulador, GPS, recuperación
real de redes, envío al backend y funcionamiento con pantalla apagada en Samsung,
Oppo y Motorola.
