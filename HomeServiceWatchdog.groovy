import groovy.transform.Field

/*
 * Home Service Watchdog
 *
 * Watches periodic check-ins from automations, bridges, scripts and local
 * services, then exposes late and failed states for Hubitat automations.
 *
 * Author: Jon Wallace
 * Copyright 2026 Jon Wallace
 * License: MIT
 * Version: 1.0.0
 */

metadata {
  definition(
    name: "Home Service Watchdog",
    namespace: "jonw",
    author: "Jon Wallace",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/b69ca/hubitat-homeservicewatchdog/main/HomeServiceWatchdog.groovy"
  ) {
    capability "Sensor"
    capability "ContactSensor"
    capability "PresenceSensor"
    capability "Refresh"
    capability "Initialize"
    capability "PushableButton"

    command "checkIn", [[name:"Source name", type:"STRING", description:"Configured source name; may be blank when only one source is configured"]]
    command "checkInAll"
    command "snooze", [[name:"Minutes", type:"NUMBER", description:"Pause failure evaluation for 1 to 1440 minutes"]]
    command "resume"
    command "resetWatchdog"
    command "clearHistory"
    command "testRecoveryEvent"
    command "testLateEvent"
    command "testFailureEvent"
    command "testReminderEvent"

    attribute "watchState", "enum", ["setup", "starting", "healthy", "late", "failed", "snoozed", "error"]
    attribute "configuredSources", "number"
    attribute "healthySources", "number"
    attribute "lateSources", "number"
    attribute "failedSources", "number"
    attribute "sourceStatus", "string"
    attribute "problemSources", "string"
    attribute "lastCheckIn", "string"
    attribute "lastCheckInSource", "string"
    attribute "lastEvaluated", "string"
    attribute "secondsToNextDeadline", "number"
    attribute "failureSince", "string"
    attribute "failureSinceEpoch", "number"
    attribute "failureDurationMinutes", "number"
    attribute "lastFailureMinutes", "number"
    attribute "totalFailures", "number"
    attribute "snoozedUntil", "string"
    attribute "snoozedUntilEpoch", "number"
    attribute "conditionSummary", "string"
    attribute "lastError", "string"
    attribute "notificationText", "string"
    attribute "notificationType", "enum", ["recovery", "late", "failure", "reminder", "test"]
  }

  preferences {
    input name: "setupNotes", type: "paragraph", title: "Home Service Watchdog 1.0.0",
      description: "Monitors expected check-ins entirely on the hub. Use Rule Machine custom actions, Maker API, webCoRE or another integration to call Check In. No account, API key or companion service is required."
    input name: "sources", type: "textarea", title: "Expected sources (one name per line, maximum 8)", required: true,
      defaultValue: "Primary automation"
    input name: "lateAfterMinutes", type: "number", title: "Mark a source late after this many minutes", range: "1..1439", defaultValue: 5
    input name: "failAfterMinutes", type: "number", title: "Mark a source failed after this many minutes", range: "2..1440", defaultValue: 10
    input name: "startupGraceMinutes", type: "number", title: "Grace period after initialization", range: "0..1440", defaultValue: 10
    input name: "evaluationMinutes", type: "enum", title: "Evaluation interval", options: [
      "1":"Every minute", "2":"Every 2 minutes", "5":"Every 5 minutes", "10":"Every 10 minutes"], defaultValue: "1"

    input name: "automationNotes", type: "paragraph", title: "Automation events",
      description: "Button 1: recovery. Button 2: one or more sources became late. Button 3: a source failed. Button 4: continuing-failure reminder. Presence becomes not present only for failure; contact opens for late or failed."
    input name: "emitLateEvent", type: "bool", title: "Emit button 2 when the watchdog becomes late", defaultValue: true
    input name: "emitRecoveryEvent", type: "bool", title: "Emit button 1 after late or failed state clears", defaultValue: true
    input name: "failureReminderMinutes", type: "enum", title: "Repeat button 4 while failed", options: [
      "0":"Never", "5":"Every 5 minutes", "15":"Every 15 minutes", "30":"Every 30 minutes", "60":"Every hour", "180":"Every 3 hours"], defaultValue: "0"
    input name: "logEnable", type: "bool", title: "Debug logging (automatically disables after 30 minutes)", defaultValue: false
  }
}

@Field static final int MAX_SOURCES = 8
@Field static final int NUMBER_OF_BUTTONS = 4

def installed() {
  unschedule()
  state.configured = false
  state.checkIns = [:]
  state.totalFailures = 0
  publishButtonCount()
  publishSafeState("setup", "Save preferences to start the watchdog")
  sendChanged("configuredSources", 0)
  sendChanged("healthySources", 0)
  sendChanged("lateSources", 0)
  sendChanged("failedSources", 0)
  sendChanged("totalFailures", 0)
}

def updated() {
  state.configured = true
  initializeWatchdog("preferences saved")
}

def initialize() {
  if (state.configured != true) {
    publishButtonCount()
    publishSafeState("setup", "Save preferences to start the watchdog")
    return
  }
  initializeWatchdog("hub startup or initialize command")
}

def uninstalled() {
  unschedule()
}

private void initializeWatchdog(String reason) {
  try {
    List configured = configuredSourceRecords()
    validateTiming()
    unschedule()
    retainConfiguredCheckIns(configured)
    long current = now()
    state.cycleStartedEpoch = current
    state.graceUntilEpoch = current + numberSetting("startupGraceMinutes", 10, 0, 1440) * 60000L
    state.snoozedUntilEpoch = 0L
    state.lastState = "starting"
    state.lastReminderEpoch = 0L
    publishButtonCount()
    sendChanged("configuredSources", configured.size())
    sendChanged("snoozedUntil", "")
    sendChanged("snoozedUntilEpoch", 0)
    sendChanged("lastError", "")
    configureSchedule()
    if (settings?.logEnable) runIn(1800, "logsOff", [overwrite:true])
    evaluateWatchdog()
    if (settings?.logEnable) log.debug "Watchdog initialized (${reason}) for ${configured.size()} source(s)"
  } catch (IllegalArgumentException e) {
    recordConfigurationError(e.message)
  }
}

private void configureSchedule() {
  int minutes = numberSetting("evaluationMinutes", 1, 1, 10)
  schedule("11 */${minutes} * ? * *", "evaluateWatchdog")
}

def checkIn(String sourceName = "") {
  if (state.configured != true) {
    sendChanged("lastError", "Save preferences before recording a check-in.")
    return
  }
  try {
    List configured = configuredSourceRecords()
    Map source = resolveSource(sourceName, configured)
    Map checkIns = state.checkIns instanceof Map ? state.checkIns : [:]
    checkIns[source.key] = now()
    state.checkIns = checkIns
    sendChanged("lastCheckIn", localText(now()))
    sendChanged("lastCheckInSource", source.name)
    sendChanged("lastError", "")
    if (settings?.logEnable) log.debug "Check-in received from ${source.name}"
    evaluateWatchdog()
  } catch (IllegalArgumentException e) {
    sendChanged("lastError", safeText(e.message, 180))
    log.warn "Home Service Watchdog rejected a check-in: ${safeText(e.message, 180)}"
  }
}

def checkInAll() {
  if (state.configured != true) return
  try {
    List configured = configuredSourceRecords()
    long current = now()
    Map checkIns = state.checkIns instanceof Map ? state.checkIns : [:]
    configured.each { checkIns[it.key] = current }
    state.checkIns = checkIns
    sendChanged("lastCheckIn", localText(current))
    sendChanged("lastCheckInSource", "All sources")
    sendChanged("lastError", "")
    evaluateWatchdog()
  } catch (IllegalArgumentException e) {
    recordConfigurationError(e.message)
  }
}

def evaluateWatchdog() {
  if (state.configured != true) return
  List configured
  try {
    configured = configuredSourceRecords()
    validateTiming()
  } catch (IllegalArgumentException e) {
    recordConfigurationError(e.message)
    return
  }
  long current = now()
  sendChanged("lastEvaluated", localText(current))

  long snoozedUntil = (state.snoozedUntilEpoch ?: 0L) as Long
  if (snoozedUntil > current) {
    long remaining = Math.max(0L, Math.ceil((snoozedUntil - current) / 1000d) as Long)
    publishCounts(configured.size(), 0, 0)
    sendChanged("watchState", "snoozed")
    sendChanged("contact", "closed")
    sendChanged("presence", "present")
    sendChanged("secondsToNextDeadline", remaining)
    sendChanged("conditionSummary", "Snoozed · resumes in ${friendlyDuration(remaining)}")
    return
  }
  if (snoozedUntil > 0L) {
    state.snoozedUntilEpoch = 0L
    sendChanged("snoozedUntil", "")
    sendChanged("snoozedUntilEpoch", 0)
  }

  long graceUntil = (state.graceUntilEpoch ?: 0L) as Long
  List statuses = sourceStatuses(configured, current)
  if (current < graceUntil) {
    long remaining = Math.max(0L, Math.ceil((graceUntil - current) / 1000d) as Long)
    publishStatusText(statuses)
    publishCounts(configured.size(), 0, 0)
    sendChanged("watchState", "starting")
    sendChanged("contact", "closed")
    sendChanged("presence", "present")
    sendChanged("secondsToNextDeadline", remaining)
    sendChanged("conditionSummary", "Starting · grace ends in ${friendlyDuration(remaining)}")
    state.lastState = "starting"
    return
  }

  List failed = statuses.findAll { it.state == "failed" }
  List late = statuses.findAll { it.state == "late" }
  List healthy = statuses.findAll { it.state == "healthy" }
  String next = failed ? "failed" : late ? "late" : "healthy"
  String previous = state.lastState ?: "starting"
  publishStatusText(statuses)
  publishCounts(healthy.size(), late.size(), failed.size())
  sendChanged("watchState", next)
  sendChanged("contact", next == "healthy" ? "closed" : "open")
  sendChanged("presence", next == "failed" ? "not present" : "present")
  sendChanged("problemSources", (failed + late).collect { it.name }.join(", "))
  sendChanged("secondsToNextDeadline", secondsToDeadline(statuses, current))
  updateFailureHistory(next, previous, current)
  sendChanged("conditionSummary", conditionSummary(next, healthy.size(), late, failed))
  emitTransition(next, previous, late, failed)
  state.lastState = next
  maybeSendReminder(next, failed, current)
}

private List sourceStatuses(List configured, long current) {
  long lateMs = numberSetting("lateAfterMinutes", 5, 1, 1439) * 60000L
  long failMs = numberSetting("failAfterMinutes", 10, 2, 1440) * 60000L
  long fallback = (state.cycleStartedEpoch ?: current) as Long
  Map checkIns = state.checkIns instanceof Map ? state.checkIns : [:]
  return configured.collect { source ->
    long checked = (checkIns[source.key] ?: fallback) as Long
    long age = Math.max(0L, current - checked)
    String status = age >= failMs ? "failed" : age >= lateMs ? "late" : "healthy"
    [key:source.key, name:source.name, checked:checked, age:age, state:status,
      nextDeadline:status == "healthy" ? checked + lateMs : status == "late" ? checked + failMs : 0L]
  }
}

private void publishStatusText(List statuses) {
  String text = statuses.collect { item ->
    long ageMinutes = Math.floor((item.age as Long) / 60000d) as Long
    "${item.name}: ${item.state.toUpperCase()} (${ageMinutes}m)"
  }.join(" · ")
  sendChanged("sourceStatus", safeText(text, 1024))
}

private void publishCounts(int healthy, int late, int failed) {
  sendChanged("healthySources", healthy)
  sendChanged("lateSources", late)
  sendChanged("failedSources", failed)
}

private long secondsToDeadline(List statuses, long current) {
  List deadlines = statuses.findAll { (it.nextDeadline as Long) > current }.collect { it.nextDeadline as Long }
  return deadlines ? Math.max(0L, Math.ceil((deadlines.min() - current) / 1000d) as Long) : 0L
}

private void updateFailureHistory(String next, String previous, long current) {
  if (next == "failed" && previous != "failed") {
    state.failureSinceEpoch = current
    state.totalFailures = ((state.totalFailures ?: 0) as Integer) + 1
    state.lastReminderEpoch = current
  }
  if (next != "failed" && previous == "failed") {
    long started = (state.failureSinceEpoch ?: current) as Long
    long duration = Math.max(0L, Math.round((current - started) / 60000d))
    state.lastFailureMinutes = duration
    state.failureSinceEpoch = 0L
    sendChanged("lastFailureMinutes", duration)
  }
  long activeSince = (state.failureSinceEpoch ?: 0L) as Long
  sendChanged("failureSince", activeSince > 0L ? localText(activeSince) : "")
  sendChanged("failureSinceEpoch", activeSince)
  sendChanged("failureDurationMinutes", activeSince > 0L ? Math.max(0L, Math.floor((current - activeSince) / 60000d) as Long) : 0)
  sendChanged("totalFailures", (state.totalFailures ?: 0) as Integer)
}

private String conditionSummary(String next, int healthy, List late, List failed) {
  if (next == "failed") return "Failed · ${failed*.name.join(', ')} · ${healthy} healthy"
  if (next == "late") return "Late · ${late*.name.join(', ')} · ${healthy} healthy"
  return "Healthy · ${healthy} of ${healthy} sources checked in"
}

private void emitTransition(String next, String previous, List late, List failed) {
  if (next == previous || previous == "starting" || previous == "setup" || previous == "error" || previous == "snoozed") return
  if (next == "failed") {
    emitNotification(3, "failure", "Watchdog failure: ${failed*.name.join(', ')} missed the failure deadline.")
  } else if (next == "late" && settings?.emitLateEvent != false) {
    emitNotification(2, "late", "Watchdog late: ${late*.name.join(', ')} missed the expected check-in time.")
  } else if (next == "healthy" && settings?.emitRecoveryEvent != false) {
    emitNotification(1, "recovery", "Home Service Watchdog recovered; all configured sources are healthy.")
  }
}

private void maybeSendReminder(String next, List failed, long current) {
  int minutes = numberSetting("failureReminderMinutes", 0, 0, 180)
  if (next != "failed" || minutes <= 0) return
  long previous = (state.lastReminderEpoch ?: current) as Long
  if (current - previous >= minutes * 60000L) {
    state.lastReminderEpoch = current
    emitNotification(4, "reminder", "Watchdog reminder: ${failed*.name.join(', ')} remains failed.")
  }
}

def snooze(BigDecimal minutes) {
  int duration
  try {
    duration = Math.max(1, Math.min(1440, minutes?.intValue() ?: 0))
  } catch (Exception ignored) {
    duration = 1
  }
  long until = now() + duration * 60000L
  state.snoozedUntilEpoch = until
  sendChanged("snoozedUntil", localText(until))
  sendChanged("snoozedUntilEpoch", until)
  evaluateWatchdog()
}

def resume() {
  state.snoozedUntilEpoch = 0L
  sendChanged("snoozedUntil", "")
  sendChanged("snoozedUntilEpoch", 0)
  evaluateWatchdog()
}

def resetWatchdog() {
  state.checkIns = [:]
  state.failureSinceEpoch = 0L
  state.lastReminderEpoch = 0L
  state.cycleStartedEpoch = now()
  state.graceUntilEpoch = now() + numberSetting("startupGraceMinutes", 10, 0, 1440) * 60000L
  state.lastState = "starting"
  evaluateWatchdog()
}

def clearHistory() {
  state.totalFailures = 0
  state.lastFailureMinutes = 0L
  sendChanged("totalFailures", 0)
  sendChanged("lastFailureMinutes", 0)
}

def refresh() { evaluateWatchdog() }

def logsOff() {
  device.updateSetting("logEnable", [value:"false", type:"bool"])
}

def testRecoveryEvent() { emitNotification(1, "test", "TEST: Home Service Watchdog recovery event.") }
def testLateEvent() { emitNotification(2, "test", "TEST: Home Service Watchdog late event.") }
def testFailureEvent() { emitNotification(3, "test", "TEST: Home Service Watchdog failure event.") }
def testReminderEvent() { emitNotification(4, "test", "TEST: Home Service Watchdog reminder event.") }

private List configuredSourceRecords() {
  String raw = settings?.sources?.toString() ?: ""
  List names = raw.readLines().collect { safeText(it, 60) }.findAll { it }
  if (!names) throw new IllegalArgumentException("Enter at least one expected source name.")
  if (names.size() > MAX_SOURCES) throw new IllegalArgumentException("Configure no more than ${MAX_SOURCES} sources.")
  Map unique = [:]
  names.each { String name ->
    String key = sourceKey(name)
    if (unique.containsKey(key)) throw new IllegalArgumentException("Source names must be unique; '${name}' is duplicated.")
    unique[key] = [key:key, name:name]
  }
  return unique.values() as List
}

private void validateTiming() {
  int late = numberSetting("lateAfterMinutes", 5, 1, 1439)
  int failed = numberSetting("failAfterMinutes", 10, 2, 1440)
  if (failed <= late) throw new IllegalArgumentException("Failure time must be greater than the late time.")
}

private Map resolveSource(String requested, List configured) {
  String text = requested?.trim()
  if (!text && configured.size() == 1) return configured[0]
  if (!text) throw new IllegalArgumentException("Provide a source name when more than one source is configured.")
  String key = sourceKey(text)
  Map result = configured.find { it.key == key }
  if (!result) throw new IllegalArgumentException("Unknown source '${safeText(text, 60)}'. Use a name from Expected sources.")
  return result
}

private void retainConfiguredCheckIns(List configured) {
  Map previous = state.checkIns instanceof Map ? state.checkIns : [:]
  Map retained = [:]
  configured.each { if (previous[it.key] != null) retained[it.key] = previous[it.key] }
  state.checkIns = retained
}

private void recordConfigurationError(String message) {
  unschedule()
  String detail = safeText(message ?: "The watchdog configuration is invalid.", 180)
  publishButtonCount()
  publishSafeState("error", "Configuration error · ${detail}")
  sendChanged("lastError", detail)
  log.warn "Home Service Watchdog configuration error: ${detail}"
}

private void publishSafeState(String watchState, String summary) {
  sendChanged("watchState", watchState)
  sendChanged("contact", "closed")
  sendChanged("presence", "present")
  sendChanged("secondsToNextDeadline", 0)
  sendChanged("conditionSummary", summary)
}

private void emitNotification(int button, String type, String text) {
  String safe = safeText(text, 240)
  sendEvent(name:"notificationText", value:safe)
  sendEvent(name:"notificationType", value:type)
  sendEvent(name:"pushed", value:button, isStateChange:true, descriptionText:safe)
}

private void publishButtonCount() {
  sendEvent(name:"numberOfButtons", value:NUMBER_OF_BUTTONS)
}

private int numberSetting(String name, int fallback, int minimum, int maximum) {
  try {
    int value = settings?.get(name) != null ? settings.get(name).toString().toBigDecimal().intValue() : fallback
    return Math.max(minimum, Math.min(maximum, value))
  } catch (Exception ignored) {
    return fallback
  }
}

private String sourceKey(String value) {
  return value.toLowerCase().replaceAll(/\s+/, " ").trim()
}

private String localText(long epoch) {
  TimeZone zone = location?.timeZone ?: TimeZone.getTimeZone("UTC")
  return new Date(epoch).format("yyyy-MM-dd HH:mm:ss z", zone)
}

private String friendlyDuration(long seconds) {
  long safe = Math.max(0L, seconds)
  if (safe < 60L) return "${safe} second${safe == 1L ? '' : 's'}"
  long minutes = Math.ceil(safe / 60d) as Long
  if (minutes < 60L) return "${minutes} minute${minutes == 1L ? '' : 's'}"
  long hours = Math.ceil(minutes / 60d) as Long
  return "${hours} hour${hours == 1L ? '' : 's'}"
}

private String safeText(Object value, int maximum) {
  String text = value == null ? "" : value.toString().replaceAll(/[\r\n\t]+/, " ").trim()
  return text.size() <= maximum ? text : text.take(Math.max(0, maximum - 1)) + "…"
}

private void sendChanged(String name, Object value) {
  if (device.currentValue(name)?.toString() != value?.toString()) sendEvent(name:name, value:value)
}
