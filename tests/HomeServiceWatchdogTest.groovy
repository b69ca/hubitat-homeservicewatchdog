// Run from the repository root with Groovy 2.4.21.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def state = [:]
def settings = [sources:'Security bridge\nBackup notifier', lateAfterMinutes:5, failAfterMinutes:10,
  startupGraceMinutes:2, evaluationMinutes:'1', emitLateEvent:true, emitRecoveryEvent:true,
  failureReminderMinutes:'5', logEnable:false]
def readings = [:]
def events = []
def jobs = [:]
long clock = Date.parse('yyyy-MM-dd HH:mm', '2026-10-03 18:00', TimeZone.getTimeZone('UTC')).time
def location = [timeZone:TimeZone.getTimeZone('America/Denver')]

def binding = new Binding([
  state:state, settings:settings, location:location,
  metadata:{ Closure ignored -> }, now:{ -> clock },
  device:[currentValue:{ String name -> readings[name] }, updateSetting:{ String name, Map value -> settings[name] = value.value }],
  log:[warn:{ ignored -> }, debug:{ ignored -> }, info:{ ignored -> }],
  sendEvent:{ Map event -> events << event; readings[event.name] = event.value },
  runIn:{ seconds, handler, Map options = [:] -> jobs[handler] = [seconds:seconds, options:options] },
  unschedule:{ String handler = null -> if (handler) jobs.remove(handler) else jobs.clear() },
  schedule:{ expression, handler -> jobs[handler] = [cron:expression] }
])

def driver = new GroovyShell(binding).parse(new File('HomeServiceWatchdog.groovy'))
driver.run()

driver.installed()
assert readings.watchState == 'setup'
assert readings.contact == 'closed'
assert readings.presence == 'present'
assert readings.numberOfButtons == 4
assert events.findAll { it.name == 'pushed' }.empty
println 'PASS: installation remains inert and safe until preferences are saved'

driver.updated()
assert readings.watchState == 'starting'
assert readings.configuredSources == 2
assert readings.secondsToNextDeadline == 120L
assert jobs.evaluateWatchdog.cron == '11 */1 * ? * *'
assert readings.sourceStatus.contains('Security bridge: HEALTHY')
println 'PASS: configuration starts a grace period and evaluation schedule'

driver.checkIn('security BRIDGE')
assert readings.lastCheckInSource == 'Security bridge'
assert readings.lastError == ''
clock += 2 * 60000L
driver.checkIn('Security bridge')
driver.evaluateWatchdog()
assert readings.watchState == 'healthy'
assert readings.healthySources == 2
assert readings.lateSources == 0
assert readings.failedSources == 0
assert readings.contact == 'closed'
assert !events.any { it.name == 'pushed' }
println 'PASS: named check-ins are case-insensitive and grace establishes a silent healthy baseline'

clock += 3 * 60000L
driver.evaluateWatchdog()
assert readings.watchState == 'late'
assert readings.healthySources == 1
assert readings.lateSources == 1
assert readings.contact == 'open'
assert readings.presence == 'present'
assert readings.problemSources == 'Backup notifier'
assert events.findAll { it.name == 'pushed' }[-1].value == 2
assert readings.notificationType == 'late'
println 'PASS: a missed expected check-in becomes late and emits button 2'

clock += 5 * 60000L
driver.evaluateWatchdog()
assert readings.watchState == 'failed'
assert readings.failedSources == 1
assert readings.presence == 'not present'
assert readings.totalFailures == 1
assert readings.failureSinceEpoch == clock
assert events.findAll { it.name == 'pushed' }[-1].value == 3
println 'PASS: a missed failure deadline opens failure history and emits button 3'

clock += 5 * 60000L
driver.evaluateWatchdog()
assert readings.failureDurationMinutes == 5L
assert events.findAll { it.name == 'pushed' }[-1].value == 4
assert readings.notificationType == 'reminder'
println 'PASS: continuing failure updates duration and emits configured reminders'

driver.checkIn('Backup notifier')
assert readings.watchState == 'failed'
assert readings.failedSources == 1 // Security bridge is now the failed source.
driver.checkIn('Security bridge')
assert readings.watchState == 'healthy'
assert readings.presence == 'present'
assert readings.contact == 'closed'
assert readings.lastFailureMinutes == 5L
assert events.findAll { it.name == 'pushed' }[-1].value == 1
assert readings.notificationType == 'recovery'
println 'PASS: all sources must recover before button 1 and failure duration are published'

driver.snooze(10)
assert readings.watchState == 'snoozed'
assert readings.snoozedUntilEpoch == clock + 600000L
assert readings.contact == 'closed'
clock += 9 * 60000L
driver.evaluateWatchdog()
assert readings.watchState == 'snoozed'
driver.resume()
assert readings.watchState == 'late'
assert readings.snoozedUntilEpoch == 0
println 'PASS: snooze suppresses evaluation and resume immediately reconciles source ages'

driver.checkIn('unknown service')
assert readings.lastError.contains('Unknown source')
def eventCount = events.size()
driver.testRecoveryEvent(); assert events.findAll { it.name == 'pushed' }[-1].value == 1
driver.testLateEvent(); assert events.findAll { it.name == 'pushed' }[-1].value == 2
driver.testFailureEvent(); assert events.findAll { it.name == 'pushed' }[-1].value == 3
driver.testReminderEvent(); assert events.findAll { it.name == 'pushed' }[-1].value == 4
assert events.size() > eventCount
assert readings.notificationText.startsWith('TEST:')
assert readings.notificationType == 'test'
println 'PASS: unknown sources are rejected and test events do not alter health state'

settings.sources = 'Only service'
settings.startupGraceMinutes = 0
driver.updated()
driver.checkIn('')
assert readings.lastCheckInSource == 'Only service'
assert readings.watchState == 'healthy'
driver.checkInAll()
assert readings.lastCheckInSource == 'All sources'
println 'PASS: blank check-in targets the sole configured source and Check In All is supported'

settings.sources = (1..9).collect { "Service ${it}" }.join('\n')
driver.updated()
assert readings.watchState == 'error'
assert readings.lastError.contains('no more than 8')
assert !jobs.containsKey('evaluateWatchdog')
settings.sources = 'One\none'
driver.updated()
assert readings.watchState == 'error'
assert readings.lastError.contains('unique')
settings.sources = 'One'
settings.lateAfterMinutes = 10
settings.failAfterMinutes = 10
driver.updated()
assert readings.watchState == 'error'
assert readings.lastError.contains('greater than')
println 'PASS: excessive, duplicate and invalid timing configurations fail safely'

driver.clearHistory()
assert readings.totalFailures == 0
assert readings.lastFailureMinutes == 0
def restored = new JsonSlurper().parseText(JsonOutput.toJson(state))
assert restored instanceof Map
assert JsonOutput.toJson(state).length() < 8000
println 'PASS: history reset and persisted state remain compact and JSON-safe'
println 'ALL CHECKS PASSED'
