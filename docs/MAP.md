# RP Needs — карта

Версия на момент написания: 0.4.1 (16.11.2026). Факты по классам, событиям, пакетам — `docs/INDEX.md`.

## 1. Что делает и чего не делает

Нужды персонажа. Сейчас одна — **сон**: бодрость (0–100) убывает за RP-сутки, недосып копит долг и даёт стадии
(зевота → сонливость → валится с ног → микросон и обморок от усталости); лечь можно когда угодно, уснуть — когда
устал; качество сна зависит от места, черт, холода и жары, голода; будят кошмары, храп, голос, выстрелы, взрывы,
будильник; кофеин отгоняет сон. Чистота — в плане (`docs/00_plan.md`), кода нет.

За этим — не сюда:
- позы `/sit`, `/lay` и сама команда `/sleep` — RP Core (`pose/Postures`); этот мод решает, удалось ли уснуть;
- ускорение ночи, длина суток — RP Time; здесь только ответ, кого считать спящим (`RpSleepCountEvent`);
- стресс от недосыпа — RP Psyche (читает `RpCoreAPI.sleepStage`);
- снотворное, наркоз, больничная койка — мод тела (`BodyProvider.sleepAid`, `inHospitalBed`, `unconscious`).

## 2. Как течёт работа

**Тик.** `SleepService.onPlayerTick`, раз в секунду на игрока: кого не считаем (`counts`: креатив, наблюдатель) →
таймеры → на паузе RP-времени только отправка стадии → кровать: лёг — не значит уснул (`tryFallAsleepInBed`,
причины отказа — `SleepPlace.noSleepReason`) → без сознания бодрость понемногу возвращается → **спит:** скорость
восстановления = от длины суток × качество места (`SleepPlace.placeMult`) × черты × голод × условия, будильщики
(`Wakers.asleepTick`), полная бодрость без долга → `wake` → **не спит:** бодрость убывает, кофеин
(`caffeineTick`), эффекты стадии (`effects`: замедление, меньше максимум выносливости, микросон, обморок).

**Уснуть сидя или лёжа.** `/sleep` (RP Core) → `RpPostureSleepEvent` → `SleepEvents.onPostureSleep`: можно ли →
Core держит позу и затемняет экран → проснулся: `RpPostureWakeEvent` → `SleepEvents.onPostureWake` → `afterWake`.

**Что будит.** удар и взрыв — `SleepEvents.onHurt`, `onExplosion`; будильник — `RpAlarmEvent`; кошмар, храп,
голос (Simple Voice Chat, `integration/NeedsVoicePlugin`), выстрел TaCZ (`integration/TaczShots`) — `Wakers`.

**Ночь и прокрутка.** `RpSleepCountEvent` → `SleepEvents.onSleepCount` (кто спит по-настоящему); `RpTimeSkipEvent`
→ `onSkip` (сон и бодрость за прошедшее время); вышедшим — `RpTimeSkipOfflineEvent` и отметка ночи `NightMark`
(кто вышел до ночи — при входе выспался, `SleepEvents.onLogin`).

**Клиенту.** `NeedsNetwork`: `Stage` (стадия недосыпа), `Microsleep` (экран гаснет на миг), `BedSleep` (уснул ли в
кровати — темнеть ли экрану) → `client/NeedsClient`.

**Данные игрока.** раздел `rpneeds` в `getPersistentData()`; `SleepService.registerData()` описывает его для RP Core
(`PersistentTag`): после возрождения раздел переносится без сна (`asleep = false`) и тяжёлого подъёма (`heavy = 0`).

## 3. Карта пакетов

| Пакет | За что отвечает | С чего начать читать |
|---|---|---|
| `rpneeds` | весь сервер мода (пакет один) | `RpNeeds` (подписки, команда, регистрация в Core); `SleepService` — данные, тик, эффекты; `SleepPlace` — место сна и «можно ли уснуть»; `SleepEvents` — события; `Wakers` — будильщики; `NightMark` — отметка ночи; `NeedsStatus` — строки для осмотра; `NeedsConfig`; `NeedsNetwork`; `VoiceDistance` |
| `client` | затемнение, микросон, вид от первого лица в кровати | `NeedsClient` |
| `integration` | Simple Voice Chat и TaCZ (грузятся, только если мод стоит) | `NeedsVoicePlugin`, `TaczShots` |
| `gametest` | игровые тесты | `NeedsGameTests`, `NeedsEdgeTests` |

## 4. Хочу добавить… — рецепты

| Что | Где | Образец | Не забыть |
|---|---|---|---|
| новое место сна или его качество | `SleepPlace.place` (вид места) и `placeMult` | спальник: тег блоков `rpneeds:sleeping_bags` | новый блок-спальник — просто в тег, без кода |
| новую причину «не уснуть» | `SleepPlace.noSleepReason` | бессонница, холод | ключ сообщения в lang ru и en |
| новое, что будит | `Wakers` (раз в секунду спящему) или обработчик в `SleepEvents` (по событию) | храп соседа; выстрел — `TaczShots` | чужой мод — мягко, класс в `integration/` |
| новую черту сна | константа в `SleepService` (`trait("…")`), проверка `has(p, ЧЕРТА)` | `SHORT`, `INSOMNIA` | черту выдаёт RP Perks: id `rpneeds:<имя>` должен быть ему известен |
| эффект стадии недосыпа | `SleepService.effects` | замедление, максимум выносливости | снимать эффект, когда стадия ушла; на `forget` при выходе |
| настройку | `NeedsConfig` (обычный `ForgeConfigSpec`) | `FULL_SLEEP_SHARE` | описание по-русски |
| новое поле сна | ключ в теге `rpneeds` (читается и пишется на месте) | `debt` | решить, что с ним после возрождения — правило в `SleepService.registerData`; тест `NeedsEdgeTests` |
| пакет клиенту | `NeedsNetwork`, в конец | `Stage` | — |
| новую нужду (чистота) | новый сервис рядом со `SleepService` + свой `PersistentTag` или `PlayerStore` | план `docs/00_plan.md` | не складывать в `SleepService` |

## 5. Данные

| Что | Где хранится | Ключ | Что при смерти | Как попадает клиенту |
|---|---|---|---|---|
| сон игрока | игрок, `getPersistentData`, через `PersistentTag` RP Core | `rpneeds` | остаётся, но без сна и тяжёлого подъёма | стадия — `Stage` |
| отметка «ночь проспали» | мир, `SavedData` | `rpneeds_night` | — | — |

Поля раздела `rpneeds`: `alert` (бодрость), `debt` (долг сна), `asleep`, `stage`, `place`, `slept`, `rested`,
`restDay`, `heavy`, `collapse`, `micro`, `nightmare`, `cafDose`, `cafLeft`, `drunkSleep`, `cooldown`, `retry`,
`remind`, `lastWhy`, `logout`. Имена не менять: они в файлах игроков.

## 6. Связи

- Берёт из Core: `body()` (снотворное, койка, температура, боль), `unconscious`, `nutrition()`, `posture`,
  `postureSleeping`, `collapseAsleep`, `hasTrait`, `timeRunning`, `dayLengthSeconds`, `stamina()` (максимум
  выносливости); события поз, времени, будильника, `RpConsumeEvent` (кофеин), `RpCharacterWipeEvent`;
  шлёт `RpMoodEvent` (выспался, кошмар).
- Даёт в Core: `SleepProvider` (`registerSleep`), фильтр речи `rpneeds:drowsy`, строки осмотра `StatusLines`.
- Чужие: Simple Voice Chat (плагин), TaCZ (при `isLoaded`), Simple Voice Distance — через отражение
  (`VoiceDistance`, чужой мод без API).

## 7. Конфиги

`serverconfig/rpneeds-server.toml` — `NeedsConfig`, 56 ключей, обычный `ForgeConfigSpec`.

## 8. Сеть

Канал `rpneeds:main`, только сервер → клиент: `Microsleep`, `BedSleep`, `Stage`. Клиент ничего не присылает.

## 9. Ловушки

- **Всё считается от длины RP-суток** (`SleepService.day`), а не в тиках: при другой длине суток числа сами
  масштабируются. Новую скорость задавать долей суток.
- **На паузе RP-времени сон стоит** — тик выходит рано, отправив стадию.
- **Лёг в кровать ≠ уснул:** ванильный `isSleeping()` говорит только о кровати; настоящий сон — `asleep` в теге и
  `SleepService.asleep(sp)`. Другим модам — `RpCoreAPI.asleep`.
- **Голос приходит не из главного потока** (`NeedsVoicePlugin`): только запомнить, кто говорил; будить — в тике.
- Раздел `rpneeds` создаётся при первом обращении (`SleepService.data`) с бодростью 100.
- Три класса `SleepService`, `SleepPlace`, `SleepEvents` связаны статическим импортом друг друга: метод вызывается
  без имени класса, искать его надо во всех трёх (или по `docs/INDEX.md`).

## 10. Тесты

`python ../_family/family.py verify --tests "RP Needs"` — 22 игровых теста: `NeedsGameTests` (сон, время и питание
вместе с соседями по семейству в настоящем сервере), `NeedsEdgeTests` (смерть, возврат из Края, перезаход, игрок
без раздела). Меняешь правило возрождения или поля — `NeedsEdgeTests`.

## 11. Документы в docs/

| Файл | Состояние |
|---|---|
| `MAP.md`, `INDEX.md` | действуют |
| `00_plan.md` | действует: план мода (сон сделан, чистота — нет) |
| `01_hygiene_and_symptoms_proposal.md` | предложение, не принято и не реализовано |
| `02_plan_2026-11-06.md` | план 06.11: сверять с кодом, часть сделана |
