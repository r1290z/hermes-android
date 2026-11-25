# Компаньон Hermes для Pixel Watch — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Приложение часов, которое показывает состояние хода агента, отправляет промпты голосом, останавливает ход и отвечает на запросы агента — через телефон, без сети и токена на часах.

**Architecture:** Телефон остаётся единственным клиентом шлюза. Часы общаются с ним по Wearable Data Layer: `MessageClient` для команд, `DataClient` для снапшота состояния. Общий кодек протокола живёт в отдельном модуле `:watchproto`, чтобы обе стороны кодировали одинаково и кодек тестировался без устройства.

**Tech Stack:** Kotlin 2.0.21, AGP 8.9.1, Compose for Wear OS 1.7.2, `play-services-wearable:20.0.1`, JUnit 4 + Robolectric 4.11.1 (уже в проекте).

**Spec:** `docs/superpowers/specs/2026-11-22-pixel-watch-companion-design.md`

## Global Constraints

- `applicationId` обоих приложений — `ru.evgen.hermes`; подпись — `~/.hermes/secrets/hermes-android.jks` (CN=Hermes).
- `:wear`: `minSdk = 30`, `targetSdk = 34`, `compileSdk = 36`.
- `:app`: `minSdk = 24` не меняется; `targetSdk = 34` не меняется.
- Версия протокола — `1`; несовпадение мажорной версии отклоняется, а не игнорируется.
- Никаких периодических опросов и сетевых вызовов на часах; состояние обновляется только пушем с телефона.
- Пуш состояния во время стрима — не чаще одного раза в 5 секунд.
- Сборка только через `./build.sh` на внешнем билд-хосте (`HERMES_BUILD_HOST`); локальной JDK/SDK на хосте Hermes нет.
- Успех сборки — строка `BUILD SUCCESSFUL` и наличие APK; всё остальное считается провалом.
- Все строки интерфейса — на русском, как в существующем приложении.

## Review Focus

1. **Голос вернул пустую строку или пользователь отменил распознавание** — ничего не должно уйти в промпт; пустой `send` отвергается и на часах, и в мосте.
2. **Data Layer доставил состояния не по порядку** — часы обязаны отбросить снапшот с `seq` меньше показанного, иначе таймер и статус прыгают назад.
3. **Телефон убит системой, состояние устарело** — при `updatedAt` старше 90 с часы показывают «нет связи с телефоном», а не врут про идущий ход.
4. **Команда пришла, когда текущего сеанса нет** (приложение не открывали после перезагрузки) — мост отвечает `ok:false, reason:"no-session"`, часы показывают подсказку, а не молчат.
5. **Ответ на запрос пришёл после того, как запрос истёк** — мост возвращает ошибку, часы снимают экран запроса; «Разрешить» не должно применяться к следующему запросу.

---

### Task 1: Модуль `:watchproto` — кодек протокола

**Files:**
- Create: `watchproto/build.gradle.kts`
- Create: `watchproto/src/main/AndroidManifest.xml` (пустой `<manifest/>`: модуль библиотечный, но использует `org.json` из Android)
- Create: `watchproto/src/main/java/ru/evgen/hermes/watchproto/WatchState.kt`
- Create: `watchproto/src/main/java/ru/evgen/hermes/watchproto/WatchCommand.kt`
- Create: `watchproto/src/main/java/ru/evgen/hermes/watchproto/WatchCodec.kt`
- Create: `watchproto/src/main/java/ru/evgen/hermes/watchproto/TextBudget.kt`
- Test: `watchproto/src/test/java/ru/evgen/hermes/watchproto/WatchCodecTest.kt`
- Test: `watchproto/src/test/java/ru/evgen/hermes/watchproto/TextBudgetTest.kt`
- Modify: `settings.gradle.kts`, `build.gradle.kts`

**Interfaces:**
- Produces:
  - `enum class Phase { IDLE, RUNNING, WAITING, ERROR }`
  - `data class PendingRequest(val kind: Kind, val requestId: String, val title: String, val choices: List<String>)` с `enum class Kind { CLARIFY, APPROVAL }`
  - `data class WatchState(val seq: Long, val phase: Phase, val sessionId: String?, val sessionTitle: String?, val model: String?, val tail: List<String>, val elapsedSec: Int, val pending: PendingRequest?, val error: String?, val updatedAt: Long)`
  - `sealed class WatchCommand`: `Send(text)`, `Stop`, `Resume`, `Approve(requestId, choice)`, `Answer(requestId, qid, text)`, `Refresh`, `Transcript`
  - `sealed class CommandResult`: `Ok(cmd)` / `Rejected(reason)`
  - `object WatchCodec { const val PROTO = 1; fun encodeState(s: WatchState): String; fun decodeState(raw: String): WatchState?; fun encodeCommand(c: WatchCommand): String; fun decodeCommand(raw: String): CommandResult; fun encodeTranscript(lines: List<WatchLine>): String; fun decodeTranscript(raw: String): List<WatchLine> }`
  - `data class WatchLine(val role: String, val text: String)`
  - `object TextBudget { fun tail(text: String, maxLines: Int = 2, maxChars: Int = 120): List<String>; fun transcript(lines: List<WatchLine>, budget: Int = 32768, maxChars: Int = 400): List<WatchLine> }`
  - Константы путей: `Paths.CMD = "/hermes/cmd"`, `RESULT = "/hermes/result"`, `STATE = "/hermes/state"`, `TRANSCRIPT = "/hermes/transcript"`.

- [ ] **Step 1: Написать падающий тест round-trip состояния**

```kotlin
@Test fun stateRoundTrip() {
    val s = WatchState(seq = 7, phase = Phase.RUNNING, sessionId = "abc",
        sessionTitle = "Проверка логов", model = "deepseek-v4.1-flash",
        tail = listOf("первая строка", "вторая"), elapsedSec = 134,
        pending = null, error = null, updatedAt = 1732270000000L)
    assertEquals(s, WatchCodec.decodeState(WatchCodec.encodeState(s)))
}

@Test fun stateWithPendingRoundTrip() {
    val s = WatchState(seq = 9, phase = Phase.WAITING, sessionId = "abc", sessionTitle = null,
        model = null, tail = emptyList(), elapsedSec = 0,
        pending = PendingRequest(PendingRequest.Kind.APPROVAL, "req_1", "rm -rf /tmp/x", listOf("Разрешить", "Запретить")),
        error = null, updatedAt = 1L)
    assertEquals(s, WatchCodec.decodeState(WatchCodec.encodeState(s)))
}
```

- [ ] **Step 2: Запустить, убедиться, что не компилируется**

Run: `./gradlew :watchproto:test` (на билд-хосте; локально команда не запустится — JDK нет)
Expected: FAIL — unresolved reference `WatchCodec`.

- [ ] **Step 3: Реализовать `WatchState`, `PendingRequest`, `Phase`, `WatchCodec.encodeState/decodeState`**

JSON-ключи ровно: `v`, `seq`, `phase`, `sessionId`, `sessionTitle`, `model`, `tail` (массив строк), `elapsedSec`, `pending` (`{kind, requestId, title, choices}`), `error`, `updatedAt`. `decodeState` возвращает `null`, если `v != PROTO` или `phase` неизвестен. Отсутствующие `sessionId`/`sessionTitle`/`model`/`pending`/`error` декодируются в `null` (ключ может отсутствовать).

- [ ] **Step 4: Запустить тесты — PASS**

Run: `./gradlew :watchproto:test --tests '*stateRoundTrip*' --tests '*stateWithPending*'`

- [ ] **Step 5: Написать падающие тесты команд и отказов**

```kotlin
@Test fun allCommandsRoundTrip() {
    val cmds = listOf(
        WatchCommand.Send("проверь логи"), WatchCommand.Stop, WatchCommand.Resume,
        WatchCommand.Approve("req_1", "Разрешить"),
        WatchCommand.Answer("req_1", "q0", "второй вариант"),
        WatchCommand.Refresh, WatchCommand.Transcript)
    for (c in cmds) assertEquals(CommandResult.Ok(c), WatchCodec.decodeCommand(WatchCodec.encodeCommand(c)))
}

@Test fun unknownCommandRejected() =
    assertTrue(WatchCodec.decodeCommand("""{"v":1,"cmd":"selfdestruct"}""") is CommandResult.Rejected)

@Test fun wrongProtoRejected() =
    assertTrue(WatchCodec.decodeCommand("""{"v":2,"cmd":"stop"}""") is CommandResult.Rejected)

@Test fun malformedJsonRejected() =
    assertTrue(WatchCodec.decodeCommand("не json") is CommandResult.Rejected)

@Test fun emptySendRejected() =
    assertTrue(WatchCodec.decodeCommand("""{"v":1,"cmd":"send","text":"   "}""") is CommandResult.Rejected)
```

- [ ] **Step 6: Запустить — FAIL, затем реализовать `WatchCommand`, `CommandResult`, `encodeCommand`/`decodeCommand`**

Правило: `send` с пустым или пробельным `text` → `Rejected("empty")`; неизвестный `cmd` → `Rejected("unknown")`; `v != 1` → `Rejected("proto")`; битый JSON → `Rejected("json")`. Причина попадает в `/hermes/result`, чтобы на часах было видно, почему команда не сработала.

- [ ] **Step 7: Тесты бюджета текста**

```kotlin
@Test fun tailKeepsLastTwoNonEmptyLines() {
    val text = "раз\n\nдва\nтри  \n"
    assertEquals(listOf("два", "три"), TextBudget.tail(text))
}

@Test fun tailTruncatesLongLine() {
    val line = TextBudget.tail("x".repeat(300)).single()
    assertEquals(120, line.length)
    assertTrue(line.endsWith("…"))
}

@Test fun transcriptKeepsNewestWithinBudget() {
    val lines = (1..200).map { WatchLine("assistant", "строка $it " + "я".repeat(300)) }
    val out = TextBudget.transcript(lines, budget = 32768)
    assertTrue(out.last().text.startsWith("строка 200"))
    assertTrue(out.size < lines.size)
    assertTrue(WatchCodec.encodeTranscript(out).length <= 32768)
}

@Test fun transcriptTruncatesOneLongLine() {
    val out = TextBudget.transcript(listOf(WatchLine("user", "я".repeat(5000))), budget = 32768)
    assertTrue(out.single().text.length <= 400)
}
```

- [ ] **Step 8: Реализовать `TextBudget` и `encodeTranscript`/`decodeTranscript`; тесты — PASS**

`tail` берёт непустые строки с конца, максимум `maxLines`, каждую режет до `maxChars` с «…» в конце. `transcript` идёт с конца, набирает строки, пока сериализованный размер не превысит `budget`, затем разворачивает в исходный порядок.

- [ ] **Step 9: Коммит**

```bash
git add settings.gradle.kts build.gradle.kts watchproto
git commit -m "watch: модуль :watchproto — кодек протокола пульта (v1), бюджеты текста, JVM-тесты"
```

---

### Task 2: Сборка `:wear` и живая проверка Data Layer (ping)

**Files:**
- Create: `wear/build.gradle.kts`
- Create: `wear/src/main/AndroidManifest.xml`
- Create: `wear/src/main/java/ru/evgen/hermes/watch/MainActivity.kt`
- Create: `wear/src/main/java/ru/evgen/hermes/watch/PingScreen.kt`
- Create: `app/src/main/java/ru/evgen/hermes/wear/WearBridgeService.kt` (в этой задаче — только ping)
- Modify: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `settings.gradle.kts`, `build.sh`

**Interfaces:**
- Consumes: `WatchCodec`, `Paths` из Task 1.
- Produces: `WearBridgeService` как `WearableListenerService`, слушающий `Paths.CMD`; на `Refresh` отвечает `Paths.RESULT` строкой `{"ok":true,"pong":<epochMs>}`.

- [ ] **Step 1: Добавить модуль в сборку**

`settings.gradle.kts`: `include(":watchproto", ":wear")`.
`build.gradle.kts`: `id("com.android.library") version "8.9.1" apply false`, `id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false`.

- [ ] **Step 2: `wear/build.gradle.kts` с зависимостями и подписью**

`applicationId = "ru.evgen.hermes"`, `namespace = "ru.evgen.hermes.watch"`, `versionCode = 1`, `versionName = "1.0"`, `minSdk = 30`, `targetSdk = 34`, `compileSdk = 36`, `buildFeatures { compose = true }`. Зависимости: `androidx.wear.compose:compose-material3:1.7.2`, `androidx.wear.compose:compose-foundation:1.7.2`, `androidx.activity:activity-compose:1.9.3`, `com.google.android.gms:play-services-wearable:20.0.1`, `implementation(project(":watchproto"))`. Подпись — та же логика, что в `app/build.gradle.kts`: `keystore.properties` обязателен, отсутствие — `GradleException`.

- [ ] **Step 3: Манифесты**

`wear/src/main/AndroidManifest.xml`: `<uses-feature android:name="android.hardware.type.watch" android:required="true"/>`, `MainActivity` с `LAUNCHER` + intent-filter `android.intent.action.MAIN`, `android:theme` из `values/themes.xml`.
`app/src/main/AndroidManifest.xml`: внутри `<application>` —
```xml
<service android:name=".wear.WearBridgeService" android:exported="true">
  <intent-filter>
    <action android:name="com.google.android.gms.wearable.BIND_LISTENER" />
  </intent-filter>
</service>
```

- [ ] **Step 4: `WearBridgeService` — только ping**

```kotlin
class WearBridgeService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Paths.CMD) return
        val raw = String(event.data, Charsets.UTF_8)
        val cmd = WatchCodec.decodeCommand(raw)
        // Task 3 расширяет это до реальных команд; сейчас любой Refresh = pong.
    }
}
```
Отправка ответа: `Wearable.getMessageClient(this).sendMessage(nodeId, Paths.RESULT, bytes)` — `nodeId` берётся из `event.sourceNodeId`.

- [ ] **Step 5: Экран ping на часах**

`PingScreen`: кнопка «Проверить связь» → шлёт `WatchCommand.Refresh`, слушает `MessageClient` на `Paths.RESULT`, показывает «Связь есть, телефон ответил в HH:mm:ss» или «Телефон не ответил». Это временный экран, он удаляется в Task 4.

- [ ] **Step 6: `build.sh wear`**

Добавить ветку: при `$1 == "wear"` собирать `:wear:assembleRelease`, проверять подпись тем же `apksigner`/`CN=Hermes`, копировать в `wear/build/outputs/apk/release/app-release.apk` рядом с телефонным. Телефонная ветка не меняется.

- [ ] **Step 7: Собрать оба APK**

Run: `./build.sh && ./build.sh wear`
Expected: два `BUILD SUCCESSFUL`, два APK, подпись `CN=Hermes` у обоих.

- [ ] **Step 8: Живая проверка на устройствах (обязательна)**

1. `adb install -r` телефонного APK, открыть приложение, войти.
2. На часах включить отладку по Wi-Fi, `adb connect <watch-ip>:5555`, `adb install -r` wear-APK.
3. Открыть приложение на часах, нажать «Проверить связь».
Expected: на часах появляется время ответа. Если ответа нет — **остановиться и разбираться здесь**; дальнейшие задачи бессмысленны, пока Data Layer не работает на этой паре устройств.

- [ ] **Step 9: Коммит**

```bash
git add settings.gradle.kts build.gradle.kts build.sh app wear
git commit -m "watch: каркас :wear и WearBridgeService, ping через Data Layer проверен на PW1"
```

---

### Task 3: Мост — реальные команды и состояние

**Files:**
- Create: `app/src/main/java/ru/evgen/hermes/wear/CurrentSession.kt`
- Create: `app/src/main/java/ru/evgen/hermes/wear/StatePublisher.kt`
- Modify: `app/src/main/java/ru/evgen/hermes/wear/WearBridgeService.kt`
- Modify: `app/src/main/java/ru/evgen/hermes/ChatActivity.kt` (запись текущего сеанса, публикация состояния из событий хода)
- Test: `app/src/test/java/ru/evgen/hermes/wear/StatePublisherTest.kt`

**Interfaces:**
- Consumes: `ApiClient.get(ctx)`, `api.streamTurn(id, text) { ev -> }`, `api.stopTurn(id)`, `api.submitQueued(id, text)`, `api.answerApproval(id, choice)`, `api.answerClarify(id, qid, text)`, `api.messages(id, limit)`.
- Produces:
  - `object CurrentSession { fun set(ctx: Context, id: String, title: String); fun id(ctx: Context): String?; fun title(ctx: Context): String? }`
  - `interface Clock { fun now(): Long }` (реализация по умолчанию — `System.currentTimeMillis()`)
  - `class StatePublisher(ctx: Context, clock: Clock = SystemClock) { fun publish(state: WatchState); fun onTurnEvent(ev: ChatEvent); fun phase(): Phase }` — дебаунс 5 с внутри; смена фазы публикуется всегда.

- [ ] **Step 1: Тест на дебаунс и seq**

```kotlin
@Test fun publishIsDebouncedWithinFiveSeconds() {
    val p = StatePublisher(ctx, clock = fakeClock)   // clock — интерфейс, см. ниже
    p.publish(st(seq = 1)); fakeClock.advance(1000); p.publish(st(seq = 2))
    assertEquals(1, sent.size)                        // второе отброшено дебаунсом
    fakeClock.advance(5000); p.publish(st(seq = 3))
    assertEquals(2, sent.size)
}

@Test fun phaseTransitionsAreNeverDebounced() {
    val p = StatePublisher(ctx, clock = fakeClock)
    p.publish(st(seq = 1, phase = Phase.IDLE)); p.publish(st(seq = 2, phase = Phase.RUNNING))
    assertEquals(2, sent.size)                        // смена фазы идёт всегда
}
```

`clock` — `interface Clock { fun now(): Long }`, чтобы тест не спал.

- [ ] **Step 2: Реализовать `CurrentSession` и `StatePublisher` (с `Clock`), тесты — PASS**

- [ ] **Step 3: `ChatActivity` пишет текущий сеанс**

В местах, где присваивается `sessionId` (строки ~190, ~272, ~757), вызывать `CurrentSession.set(this, id, title)`. Проверка: после открытия чата `adb shell run-as ru.evgen.hermes cat shared_prefs/hermes_current.xml` содержит id.

- [ ] **Step 4: `WearBridgeService` исполняет команды**

`send`: если `phase == RUNNING` → `submitQueued` + `steerQueued` (существующий путь «докнуть в текущий ход»), иначе `streamTurn`. `stop` → `api.stopTurn(id)`. `resume` → последнее пользовательское сообщение из `api.messages(id, 50)` через `streamTurn`, только при `phase == IDLE`. `approve`/`answer` → соответствующие методы `ApiClient`. `refresh` → `publish` немедленно. Ответ на каждую команду — `/hermes/result`: `{"ok":true,"cmd":...}` или `{"ok":false,"cmd":...,"reason":"no-session"|"proto"|"unknown"|"empty"|"error"}`.

- [ ] **Step 5: Публикация состояния из чата**

`ChatActivity` на `Started`/`Delta`(дебаунс)/`Completed`/`Error`/`Done` вызывает `StatePublisher.onTurnEvent`. `tail` собирается из накопленного текста ответа через `TextBudget.tail`. `elapsedSec` — от `Started`.

- [ ] **Step 6: Проверка на устройствах**

Run: запустить ход на телефоне, `adb logcat -s WearBridge` на телефоне.
Expected: пуш `state` с `phase=running` уходит не чаще раза в 5 с; на часах (экран ping покажет последнее состояние) видно `running`.

- [ ] **Step 7: Коммит**

```bash
git add app
git commit -m "watch: мост исполняет send/stop/resume/approve/answer/refresh, публикует состояние с дебаунсом"
```

---

### Task 4: Пульт на часах

**Files:**
- Create: `wear/src/main/java/ru/evgen/hermes/watch/WatchStore.kt`
- Create: `wear/src/main/java/ru/evgen/hermes/watch/PultScreen.kt`
- Modify: `wear/src/main/java/ru/evgen/hermes/watch/MainActivity.kt`
- Delete: `wear/src/main/java/ru/evgen/hermes/watch/PingScreen.kt`
- Test: `wear/src/test/java/ru/evgen/hermes/watch/WatchStoreTest.kt`

**Interfaces:**
- Consumes: `WatchState`, `WatchCodec`, `TextBudget`, `Paths`.
- Produces: `class WatchStore(ctx: Context, clock: Clock = SystemClock) { val state: StateFlow<WatchState?>; fun onState(raw: String); fun send(cmd: WatchCommand); fun visiblePhase(): Phase; fun noLink(): Boolean }` — хранит `lastSeq` и `lastUpdatedAt`, отбрасывает устаревшее.

- [ ] **Step 1: Тест на отбрасывание устаревших снапшотов и на протухание**

```kotlin
@Test fun olderSeqIgnored() {
    val s = WatchStore(ctx); s.onState(stateJson(seq = 5)); s.onState(stateJson(seq = 4))
    assertEquals(5L, s.state.value!!.seq)
}

@Test fun staleStateReportedAsNoLink() {
    val s = WatchStore(ctx, clock = fakeClock)
    s.onState(stateJson(seq = 1, updatedAt = 1000, phase = "running"))
    fakeClock.advance(91_000)
    assertEquals(Phase.IDLE, s.visiblePhase())   // 90 с без обновления = нет связи
    assertTrue(s.noLink())
}
```

- [ ] **Step 2: Реализовать `WatchStore`, тесты — PASS**

- [ ] **Step 3: `PultScreen` — статус, таймер, кнопки**

Compose for Wear M3: `Scaffold` + `Column` со `StatusChip` (текст: `● идёт 2:14` / `○ свободен` / `⚠ ошибка` / `нет связи с телефоном`), `Text` с именем сеанса и моделью, до двух строк `tail`, ряд кнопок: `🎤`, `⏹ Стоп` (только `RUNNING`), `↻ Продолжай` (только `IDLE` при непустом последнем ходе). Таймер тикает раз в секунду только при `RUNNING` и только на экране; в ambient не тикает.

- [ ] **Step 4: `MainActivity` слушает Data Layer и показывает пульт**

`DataClient` слушатель `Paths.STATE`, `MessageClient` слушатель `Paths.RESULT`; при `onResume` отправляется `Refresh`. `PingScreen` удаляется.

- [ ] **Step 5: Собрать и проверить на часах**

Run: `./build.sh wear`, установка на часы.
Expected: при запущенном ходе на телефоне на часах `● идёт 0:12` и растущий таймер; после `Стоп` — `○ свободен`. Кнопка `Стоп` реально прерывает ход (проверить в чате телефона, что ответ оборвался).

- [ ] **Step 6: Коммит**

```bash
git add wear
git commit -m "watch: пульт — статус, таймер, Стоп и Продолжай, отбрасывание устаревших состояний"
```

---

### Task 5: Голосовой промпт

**Files:**
- Create: `wear/src/main/java/ru/evgen/hermes/watch/VoicePrompt.kt`
- Modify: `wear/src/main/java/ru/evgen/hermes/watch/PultScreen.kt`
- Modify: `wear/src/main/AndroidManifest.xml` (если требуется `<queries>` для распознавателя)

**Interfaces:**
- Consumes: `WatchStore.send(WatchCommand.Send(text))`.
- Produces: `fun recognize(activity: Activity, onResult: (String?) -> Unit)` — запускает `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` с `EXTRA_LANGUAGE = "ru-RU"`, возвращает `null` при отмене или пустом результате.

- [ ] **Step 1: Подключить распознавание и экран подтверждения**

Кнопка `🎤` → `recognize`; при непустом результате открывается экран подтверждения с полным текстом и кнопками «Отправить» / «Ещё раз» / «Отмена». При `null` — возврат на пульт без отправки (Review Focus №1).

- [ ] **Step 2: Проверка на устройстве**

Run: нажать 🎤, сказать «проверь логи за сегодня».
Expected: на экране подтверждения распознанный текст; после «Отправить» сообщение появляется в чате телефона как пользовательское, агент начинает отвечать; на часах `● идёт`.

- [ ] **Step 3: Проверка пустого результата**

Run: нажать 🎤, сказать «эээ» / отменить свайпом.
Expected: ничего не отправлено, пульт без изменений, в чате телефона новых сообщений нет.

- [ ] **Step 4: Коммит**

```bash
git add wear
git commit -m "watch: голосовой промпт с экраном подтверждения, пустой результат не отправляется"
```

---

### Task 6: Запросы агента (clarify и approval)

**Files:**
- Create: `wear/src/main/java/ru/evgen/hermes/watch/RequestScreen.kt`
- Modify: `wear/src/main/java/ru/evgen/hermes/watch/MainActivity.kt`, `WatchStore.kt`
- Modify: `app/src/main/java/ru/evgen/hermes/wear/WearBridgeService.kt` (ошибка на истёкший запрос)
- Test: `app/src/test/java/ru/evgen/hermes/wear/RequestGuardTest.kt`

**Interfaces:**
- Consumes: `WatchState.pending`, `WatchCommand.Approve`, `WatchCommand.Answer`.
- Produces: `object RequestGuard { fun stillValid(open: Set<String>, requestId: String): Boolean }` — телефон держит множество `requestId` открытых запросов; ответ на неизвестный id отклоняется с `reason:"stale"`.

- [ ] **Step 1: Тест на истёкший запрос**

```kotlin
@Test fun answerForClosedRequestRejected() {
    val open = mutableSetOf("req_1")
    assertTrue(RequestGuard.stillValid(open, "req_1"))
    assertFalse(RequestGuard.stillValid(open, "req_2"))   // истёк или чужой
}
```

- [ ] **Step 2: Реализовать `RequestGuard` в мосте, тест — PASS**

- [ ] **Step 3: `RequestScreen` на часах**

При `pending != null` — отдельный экран поверх пульта + вибрация (`Vibrator`/`VibratorManager`, один короткий паттерн). Clarify: до 4 кнопок с вариантами, плюс «Свой ответ» (голос). Approval: команда моноширинным (до 3 строк), кнопки «Разрешить» / «Запретить». После ответа экран закрывается, отправляется `Approve`/`Answer`.

- [ ] **Step 4: Проверка на устройстве**

Run: на телефоне отправить промпт, вызывающий approval (например «удали /tmp/hermes-test-req через терминал»), и отдельно — clarify.
Expected: часы вибрируют и показывают запрос; «Запретить» реально отменяет выполнение на телефоне; «Разрешить» — выполняет; после ответа экран запроса исчезает.

- [ ] **Step 5: Проверка истёкшего запроса**

Run: показать запрос на часах, подождать истечения на телефоне, нажать «Разрешить».
Expected: на часах сообщение «Запрос уже неактуален», ход не меняется.

- [ ] **Step 6: Коммит**

```bash
git add app wear
git commit -m "watch: экран запросов агента (clarify/approval) с вибрацией, защита от истёкших ответов"
```

---

### Task 7: Лента последних реплик

**Files:**
- Create: `wear/src/main/java/ru/evgen/hermes/watch/TranscriptScreen.kt`
- Modify: `wear/src/main/java/ru/evgen/hermes/watch/MainActivity.kt`, `WatchStore.kt`
- Modify: `app/src/main/java/ru/evgen/hermes/wear/WearBridgeService.kt` (`Transcript` → `api.messages`)

**Interfaces:**
- Consumes: `Paths.TRANSCRIPT`, `TextBudget.transcript`, `WatchCodec.decodeTranscript`.
- Produces: мост на `Transcript` шлёт `DataClient` данные по `Paths.TRANSCRIPT` с 5 последними репликами (`role` + `content`, усечёнными).

- [ ] **Step 1: Мост отвечает на `Transcript`**

`api.messages(id, limit = 20)` → отфильтровать `role in ("user","assistant")` и непустой текст → `TextBudget.transcript(takeLast(5))` → `DataClient.putDataItem(Paths.TRANSCRIPT, bytes)`.

- [ ] **Step 2: Экран ленты**

Свайп влево с пульта (`SwipeToDismissBox` или горизонтальный `Pager`): `LazyColumn` из 5 реплик, роль цветом (`user` — accent, `assistant` — обычный), текст переносится, реплика усечена до 400 символов. Только чтение.

- [ ] **Step 3: Проверка на устройстве**

Run: открыть чат с историей, свайпнуть влево на часах.
Expected: 5 последних реплик в правильном порядке, самая свежая внизу; длинная реплика не ломает экран.

- [ ] **Step 4: Коммит**

```bash
git add app wear
git commit -m "watch: лента последних 5 реплик сеанса"
```

---

### Task 8: Живой прогон по сценарию и публикация

**Files:**
- Modify: `app/build.gradle.kts`, `wear/build.gradle.kts` (версии), `docs/superpowers/specs/2026-11-22-pixel-watch-companion-design.md` (раздел «Проверено на устройстве» — добавить фактические результаты)

- [ ] **Step 1: Прогнать все пять сценариев из спеки, раздел 9**

Каждый — с записью фактического результата (время, что видел на экране, что в чате телефона). Пункт, который не прошёл, — не «почти работает», а дефект: заводится отдельная карточка и чинится.

- [ ] **Step 2: Проверить расход батареи**

Run: на часах `adb shell dumpsys batterystats --charged ru.evgen.hermes | head -40` до и после 30 минут с открытым пультом.
Expected: нет пробуждений по таймеру в фоне; основной расход — экран и вибрация.

- [ ] **Step 3: Поднять версии и собрать релизы**

`app`: `versionCode` +1, `versionName` 7.4; `wear`: `versionCode` 1, `versionName` "1.0".
Run: `VERSION_NAME=7.4 NOTES="Компаньон для часов" ./build.sh publish && ./build.sh wear`

- [ ] **Step 4: Установить релизные APK на телефон и часы, повторить сценарий №1**

Expected: оба приложения в одной версии протокола, пульт работает после перезапуска обоих.

- [ ] **Step 5: Коммит**

```bash
git add app wear docs
git commit -m "watch: 7.4 — компаньон для Pixel Watch, проверен на устройстве"
```
