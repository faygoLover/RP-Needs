# RP Needs — указатель кода

Создано `python _family/tools/mapgen.py` 15.11.2026, версия 0.4.0-1.20.1-forge, коммит `4b43766`.
**Руками не править**: пересоздаётся скриптом. Смысл, потоки работы и рецепты — в `docs/MAP.md`.
Разбор по шаблонам, без компиляции: необычно оформленная регистрация может отсутствовать в списках.

Всего: 12 файлов, 2046 строк Java.

## 1. Пакеты

| Пакет | Файлов | Строк |
|---|---:|---:|
| `faygolover.rpneeds` | 8 | 1323 |
| `faygolover.rpneeds.client` | 1 | 146 |
| `faygolover.rpneeds.gametest` | 1 | 524 |
| `faygolover.rpneeds.integration` | 2 | 53 |

## 2. Классы

### `faygolover.rpneeds`
- `NeedsConfig`, 198 — Настройки сервера (serverconfig/rpneeds-server.toml).
- `NeedsNetwork`, 91 — Сетевой канал rpneeds:main: микросон и «уснул ли в кровати» — самому игроку.
- `NeedsStatus`, 63 — Строки сна для RP Medicine: сам игрок — ощущениями в своих состояниях, другие при осмотре — только сильную усталость и сон, ГМ сканером и в панели — цифры.
- `NightMark`, 36 — Когда в последний раз проспали ночь (реальное время, мс): вышедшие до этого при входе выспались.
- `RpNeeds`, 123 — RP Needs — потребности: сон (0.2.0, дизайн «RP Needs: сон»), дальше чистота (план docs/00_plan.md).
- `SleepService`, 684 — Сон (сервер), дизайн «RP Needs: сон» 29–30.10.
- `VoiceDistance`, 38 — Simple Voice Distance (необязательный): режим дальности игрока на сервере.
- `Wakers`, 90 — Что будит спящего, кроме удара и Shift: кошмары, храп соседа, голос, выстрелы (взрывы и будильник — в SleepService).

### `faygolover.rpneeds.client`
- `NeedsClient`, 146 — Клиент: микросон (экран гаснет на миг), кровать без сна (лёг, но не уснул — экран не темнеет) и 1-е лицо, когда лёг в кровать или по /lay: от 3-го лица лежачий виден криво (тест 03.11); вернуть вид игрок может сам.

### `faygolover.rpneeds.gametest`
- `NeedsGameTests`, 524 — Сон, время и питание вместе с семьёй RP в настоящем сервере (без клиента).

### `faygolover.rpneeds.integration`
- `NeedsVoicePlugin`, 30 — Плагин Simple Voice Chat: кто говорит и шёпотом ли — спящих рядом будит голос.
- `TaczShots`, 23 — TaCZ (необязательный): выстрел рядом будит спящих.

Классов без описания: 0 из 12.

## 3. Подписки на события

| Событие | Кто слушает |
|---|---|
| `ExplosionEvent.Detonate` | `SleepService.onExplosion` |
| `FMLCommonSetupEvent` | `RpNeeds.commonSetup` |
| `GunShootEvent` | `TaczShots.onShoot` |
| `LivingHurtEvent` | `SleepService.onHurt` |
| `MicrophonePacketEvent` | `NeedsVoicePlugin.onMicrophone` |
| `PlayerEvent.Clone` | `SleepService.onClone` |
| `PlayerEvent.PlayerLoggedInEvent` | `SleepService.onLogin` |
| `PlayerEvent.PlayerLoggedOutEvent` | `SleepService.onLogout` |
| `RegisterCommandsEvent` | `RpNeeds.onCommands` |
| `RenderGuiEvent.Post` | `NeedsClient.eyes`, `NeedsClient.onRender` |
| `RenderGuiOverlayEvent.Pre` | `NeedsClient.onOverlay` |
| `RpAlarmEvent` | `SleepService.onAlarm` |
| `RpConsumeEvent` | `SleepService.onConsume` |
| `RpPostureSleepEvent` | `SleepService.onPostureSleep` |
| `RpPostureWakeEvent` | `SleepService.onPostureWake` |
| `RpSleepCountEvent` | `SleepService.onSleepCount` |
| `RpTimeSkipEvent` | `SleepService.onSkip` |
| `RpTimeSkipOfflineEvent` | `SleepService.onSkipOffline` |
| `SleepingTimeCheckEvent` | `SleepService.onSleepTime` |
| `TickEvent.ClientTickEvent` | `NeedsClient.onClientTick` |
| `TickEvent.PlayerTickEvent` | `SleepService.onPlayerTick` |
| `faygolover.rpcore.api.event.RpCharacterWipeEvent` | `SleepService.onWipe` |

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
- getPersistentData: `SleepService` ×5, теги: `rpneeds`

## 7. Конфиги

- server: `NeedsConfig`, вызовов define 56

## 8. Что берёт из RP Core и что ему даёт

Методы `RpCoreAPI` (сколько вызовов): `Posture` 2, `SpeechChannel` 2, `body` 6, `collapseAsleep` 1, `dayLengthSeconds` 2, `hasTrait` 2, `nutrition` 1, `posture` 2, `postureSleeping` 2, `registerModule` 1, `registerSleep` 1, `registerSpeechFilter` 1, `rpPerReal` 1, `sanity` 2, `skipFloor` 1, `staminaAttribute` 1, `stress` 2, `timeRunning` 1, `unconscious` 1, `wakeFromPosture` 1.

Реализует розетки: Source ← `NeedsStatus`.

События Core: `RpAlarmEvent`, `RpCharacterWipeEvent`, `RpConsumeEvent`, `RpMoodEvent`, `RpPostureSleepEvent`, `RpPostureWakeEvent`, `RpSleepCountEvent`, `RpTimeSkipEvent`, `RpTimeSkipOfflineEvent`.

## 9. Связи с другими модами

Проверки `isLoaded`: `tacz` (RpNeeds).

Отражение (`Class.forName`):
- `VoiceDistance` → `de.awolfynight.simplevoicedistance.voicemanager.VoiceDistanceData`

Пакеты других модов семейства в коде: `rpcore` — 5 файлов.

## 10. Зарегистрированные id

Нет или регистрируются не строковыми литералами.
