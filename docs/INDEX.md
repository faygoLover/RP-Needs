# RP Needs — указатель кода

Создано `python _family/tools/mapgen.py`, версия мода 0.4.1-1.20.1-forge.
**Руками не править**: пересоздаётся скриптом. Смысл, потоки работы и рецепты — в `docs/MAP.md`.
Разбор по шаблонам, без компиляции: необычно оформленная регистрация может отсутствовать в списках.

Всего: 15 файлов, 2191 строк Java.

## 1. Пакеты

| Пакет | Файлов | Строк |
|---|---:|---:|
| `faygolover.rpneeds` | 10 | 1368 |
| `faygolover.rpneeds.client` | 1 | 146 |
| `faygolover.rpneeds.gametest` | 2 | 624 |
| `faygolover.rpneeds.integration` | 2 | 53 |

## 2. Классы

### `faygolover.rpneeds`
- `NeedsConfig`, 198 — Настройки сервера (serverconfig/rpneeds-server.toml).
- `NeedsNetwork`, 91 — Сетевой канал rpneeds:main: микросон и «уснул ли в кровати» — самому игроку.
- `NeedsStatus`, 63 — Строки сна для RP Medicine: сам игрок — ощущениями в своих состояниях, другие при осмотре — только сильную усталость и сон, ГМ сканером и в панели — цифры.
- `NightMark`, 36 — Когда в последний раз проспали ночь (реальное время, мс): вышедшие до этого при входе выспались.
- `RpNeeds`, 126 — RP Needs — потребности: сон (0.2.0, дизайн «RP Needs: сон»), дальше чистота (план docs/00_plan.md).
- `SleepEvents`, 184 — События, на которые отвечает сон: ванильная проверка времени сна, счёт спящих для ускорения ночи, позы RP Core, урон и взрывы (будят), будильник, кофеин, прокрутка времени, вход и выход игрока, вайп персонажа.
- `SleepPlace`, 118 — Где и можно ли спать: место сна (кровать, спальник, койка, поза), его качество, причины «не уснуть», ночь, бессонница, холод.
- `SleepService`, 424 — Сон (сервер), дизайн «RP Needs: сон» 29–30.10.
- `VoiceDistance`, 38 — Simple Voice Distance (необязательный): режим дальности игрока на сервере.
- `Wakers`, 90 — Что будит спящего, кроме удара и Shift: кошмары, храп соседа, голос, выстрелы (взрывы и будильник — в SleepService).

### `faygolover.rpneeds.client`
- `NeedsClient`, 146 — Клиент: микросон (экран гаснет на миг), кровать без сна (лёг, но не уснул — экран не темнеет) и 1-е лицо, когда лёг в кровать или по /lay: от 3-го лица лежачий виден криво (тест 03.11); вернуть вид игрок может сам.

### `faygolover.rpneeds.gametest`
- `NeedsEdgeTests`, 100 — Края состояния сна: смерть, возврат из Края, перезаход.
- `NeedsGameTests`, 524 — Сон, время и питание вместе с семьёй RP в настоящем сервере (без клиента).

### `faygolover.rpneeds.integration`
- `NeedsVoicePlugin`, 30 — Плагин Simple Voice Chat: кто говорит и шёпотом ли — спящих рядом будит голос.
- `TaczShots`, 23 — TaCZ (необязательный): выстрел рядом будит спящих.

Классов без описания: 0 из 15.

## 3. Подписки на события

| Событие | Кто слушает |
|---|---|
| `ExplosionEvent.Detonate` | `SleepEvents.onExplosion` |
| `FMLCommonSetupEvent` | `RpNeeds.commonSetup` |
| `GunShootEvent` | `TaczShots.onShoot` |
| `LivingHurtEvent` | `SleepEvents.onHurt` |
| `MicrophonePacketEvent` | `NeedsVoicePlugin.onMicrophone` |
| `PlayerEvent.PlayerLoggedInEvent` | `SleepEvents.onLogin` |
| `PlayerEvent.PlayerLoggedOutEvent` | `SleepEvents.onLogout` |
| `RegisterCommandsEvent` | `RpNeeds.onCommands` |
| `RenderGuiEvent.Post` | `NeedsClient.eyes`, `NeedsClient.onRender` |
| `RenderGuiOverlayEvent.Pre` | `NeedsClient.onOverlay` |
| `RpAlarmEvent` | `SleepEvents.onAlarm` |
| `RpConsumeEvent` | `SleepEvents.onConsume` |
| `RpPostureSleepEvent` | `SleepEvents.onPostureSleep` |
| `RpPostureWakeEvent` | `SleepEvents.onPostureWake` |
| `RpSleepCountEvent` | `SleepEvents.onSleepCount` |
| `RpTimeSkipEvent` | `SleepEvents.onSkip` |
| `RpTimeSkipOfflineEvent` | `SleepEvents.onSkipOffline` |
| `SleepingTimeCheckEvent` | `SleepEvents.onSleepTime` |
| `TickEvent.ClientTickEvent` | `NeedsClient.onClientTick` |
| `TickEvent.PlayerTickEvent` | `SleepService.onPlayerTick` |
| `faygolover.rpcore.api.event.RpCharacterWipeEvent` | `SleepEvents.onWipe` |

## 4. Сеть

Канал: `faygolover/rpneeds/NeedsNetwork.java`, имя `main`, версия протокола `не объявлена константой`.
- `Microsleep` (направление не указано в регистрации)
- `BedSleep` (направление не указано в регистрации)
- `Stage` (направление не указано в регистрации)

Классы с обработчиками пакетов (1): `NeedsNetwork`.

## 5. Команды

- `faygolover/rpneeds/RpNeeds.java`: `rpneeds` `set` `sleep` `reset`

## 6. Данные

- SavedData: `NightMark`, файл "rpneeds_night"
- getPersistentData: `SleepEvents` ×1
- getPersistentData: `SleepService` ×3, теги: `rpneeds`
- getPersistentData: `NeedsEdgeTests` ×9, теги: `rpneeds`

## 7. Конфиги

- server: `NeedsConfig`, вызовов define 56

## 8. Что берёт из RP Core и что ему даёт

Методы `RpCoreAPI` (сколько вызовов): `Posture` 2, `SpeechChannel` 2, `body` 6, `collapseAsleep` 1, `dayLengthSeconds` 2, `hasTrait` 2, `nutrition` 1, `posture` 2, `postureSleeping` 2, `registerModule` 1, `registerSleep` 1, `registerSpeechFilter` 1, `reportCompat` 2, `rpPerReal` 1, `sanity` 2, `skipFloor` 1, `staminaAttribute` 1, `stress` 2, `timeRunning` 1, `unconscious` 1, `wakeFromPosture` 1.

Реализует розетки: Source ← `NeedsStatus`.

События Core: `RpAlarmEvent`, `RpCharacterWipeEvent`, `RpConsumeEvent`, `RpMoodEvent`, `RpPostureSleepEvent`, `RpPostureWakeEvent`, `RpSleepCountEvent`, `RpTimeSkipEvent`, `RpTimeSkipOfflineEvent`.

## 9. Связи с другими модами

Проверки `isLoaded`: `tacz` (RpNeeds).

Отражение (`Class.forName`):
- `VoiceDistance` → `de.awolfynight.simplevoicedistance.voicemanager.VoiceDistanceData`

Пакеты других модов семейства в коде: `rpcore` — 7 файлов.

## 10. Зарегистрированные id

Нет или регистрируются не строковыми литералами.
