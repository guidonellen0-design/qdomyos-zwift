package com.nettarion.hyperborea.hardware.fitpro.v1

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.ConsoleKey
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.isBeltBased
import com.nettarion.hyperborea.hardware.fitpro.session.DeviceDatabase
import com.nettarion.hyperborea.hardware.fitpro.session.ExerciseDataAccumulator
import com.nettarion.hyperborea.hardware.fitpro.session.FitProSession
import com.nettarion.hyperborea.hardware.fitpro.session.GripHeartRateFilter
import com.nettarion.hyperborea.hardware.fitpro.session.PowerEstimator
import com.nettarion.hyperborea.hardware.fitpro.session.SessionState
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class V1Session(
    private val transport: HidTransport,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val deviceInfo: DeviceInfo,
    private val accumulator: ExerciseDataAccumulator = ExerciseDataAccumulator(),
) : FitProSession {

    private val _exerciseData = MutableStateFlow<ExerciseData?>(null)
    override val exerciseData: StateFlow<ExerciseData?> = _exerciseData.asStateFlow()

    private val _deviceIdentity = MutableStateFlow<DeviceIdentity?>(null)
    override val deviceIdentity: StateFlow<DeviceIdentity?> = _deviceIdentity.asStateFlow()

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _consoleKeyPresses =
        MutableSharedFlow<ConsoleKey>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val consoleKeyPresses: SharedFlow<ConsoleKey> = _consoleKeyPresses.asSharedFlow()

    private val _degradedReason = MutableStateFlow<String?>(null)
    override val degradedReason: StateFlow<String?> = _degradedReason.asStateFlow()

    private var pollJob: Job? = null
    private var pendingWriteFields: Map<V1DataField, Float> = emptyMap()
    private val pendingWriteMutex = Mutex()
    /** Serializes complete HID request/response transactions with multi-step safety commands. */
    private val ioMutex = Mutex()
    /** Preserves command ordering; in particular Resume cannot interleave with Stop. */
    private val commandMutex = Mutex()
    @Volatile private var pendingCalibration: CompletableDeferred<Unit>? = null
    private var lastLogTimeMs = 0L
    private var consecutivePollErrors = 0
    private var lastSentGrade = 0f
    private var lastSentSpeed = 0f
    private var lastKeyCode = -1 // for KEY_OBJECT press-edge detection
    private var lastFanRaw = -1 // edge-triggered FAN_STATE diagnostic
    private val resistance = ResistanceConverter(deviceInfo.maxResistance)
    private var gearSeen = false // GEAR is authoritative once the board proves it reports it
    private var gearAddressed = false // bike board that declares GEAR: brake is addressed by gear
    private var lastCommandedGear = -1 // what we last wrote to GEAR, for the telemetry line
    private var lastBrakeLevel = -1 // RESISTANCE field, kept for diagnostics only
    private val gripHeartRate = GripHeartRateFilter()

    /** Device capabilities read from MCU during handshake. */
    var capabilities: V1Capabilities? = null
        private set

    /** Power-curve table index for this device, resolved during the handshake. */
    var powerCurveIndex: Int? = null
        private set

    // Security handshake state — stored for SECURITY_BLOCK re-verification
    private var softwareVersion: Int = 0
    private var hardwareVersion: Int = 0
    private var serialNumber: Int = 0
    private var partNumber: Int = 0
    private var model: Int = 0
    private var masterLibraryVersion: Int = 0

    /** Bitfield indices ([V1DataField.fieldIndex]) the device declared it supports; empty if it couldn't be read. */
    private var supportedBitFields: Set<Int> = emptySet()

    /**
     * Equipment type as detected from the MCU's own `Connect` device-id response. The constructor's
     * [deviceInfo] arrives here from [com.nettarion.hyperborea.hardware.fitpro.session.DeviceDatabase.fromProductId],
     * which only knows the USB product id and defaults [DeviceType.BIKE] for every FitPro device —
     * so its `.type` cannot be trusted during [prepareConsole]/[transitionToActive]. The MCU's
     * equipment id, captured in [handshake] and mapped via [DeviceDatabase.deviceTypeFromEquipmentId],
     * is the ground truth at this stage. [com.nettarion.hyperborea.hardware.fitpro.FitProAdapter]
     * reads this back through [FitProSession.detectedDeviceType] after [start] returns and uses it
     * to refine the adapter-level [DeviceInfo.type].
     */
    override var detectedDeviceType: DeviceType = DeviceType.BIKE
        private set

    /**
     * The actual set we poll for each loop iteration, narrowed to what the device claims to support.
     * Filtering matters because [V1Codec.decodeDataResponseForFields] decodes the response as a flat
     * blob in field-index order with no per-field presence check, so asking for a field the MCU
     * doesn't supply causes every subsequent field to land on the wrong offset (the bug behind the
     * NordicTrack 2950 Argon-firmware -10595 kcal / 139 km screenshot).
     */
    private var pollFields: Set<V1DataField> = V1DataField.periodicReadFields

    /** Tracks whether the previous poll's response was flagged truncated, so we log only on the edge. */
    private var lastTruncatedSeen: Boolean = false

    /**
     * Whether each poll cycle is chased with a KEY_OBJECT-only read. KEY_OBJECT (14B) cannot ride
     * in [pollFields]: this console's reply cap (see the read-budget notes) would silently truncate
     * every field behind it. A second tiny read keeps the main poll shape untouched and still
     * samples the keypad ~each cycle.
     */
    private var pollKeypad = false
    private var loggedPayloadBaseline = false

    /**
     * Workout-state watchdog. The console's own state machine leaves RUNNING by itself - its
     * pause/idle timeout after the rider stops pedalling is enough - and on this controller the
     * bike's virtual speed field ACTUAL_KPH is only produced while RUNNING. RPM keeps reporting
     * either way, which is exactly what the failure looks like from the app: cadence moves,
     * speed sits at 0, and with it distance (QZ integrates distance from speed) and the
     * estimated power (PowerEstimator is driven by speed, and returns null at 0 kph).
     *
     * [transitionToActive] ran once at session start and nothing re-asserted it, so one timeout
     * left every later ride reading 0 kph until QZ was restarted. Measured on the S22i
     * 2026-09-02: console in IDLE, 57 rpm -> 0.0 kph 0 W; the same pedalling after re-driving
     * IDLE -> WARM_UP -> RUNNING by hand -> 11.18 kph 19 W.
     *
     * [appRequestedPause] keeps the watchdog from fighting a pause the app asked for: only a
     * console-initiated drop-out is recovered.
     */
    private var appRequestedPause = false
    private var workoutWatchdogBackoffMs = WORKOUT_WATCHDOG_MIN_BACKOFF_MS
    private var workoutWatchdogNextAttemptMs = 0L

    override suspend fun start() {
        if (_sessionState.value is SessionState.Streaming || _sessionState.value is SessionState.Connecting) return

        try {
            _sessionState.value = SessionState.Connecting
            transport.open()

            _sessionState.value = SessionState.Handshaking
            transport.clearBuffer()
            handshake()

            // Console init is done while still in IDLE. Belt motion must only become possible after
            // an explicit ResumeWorkout command; non-treadmills retain their activation sequence.
            prepareConsole()
            accumulator.start()
            transitionToActive()

            _sessionState.value = SessionState.Streaming
            startPollLoop()
            logger.i(TAG, "V1 session started")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start V1 session", e)
            try { transport.close() } catch (_: Exception) {}
            _sessionState.value = SessionState.Error(e.message ?: "V1 session failed", e)
        }
    }

    override suspend fun stop() {
        // Stop the poll loop and wait (briefly) for it to actually exit before we touch the transport
        // — gracefulEndForDisconnect does its own request/response round-trips and must not race the
        // poll's reads. Bounded so a wedged MCU read can't hang teardown.
        pollJob?.let { job ->
            job.cancel()
            withTimeoutOrNull(POLL_JOIN_TIMEOUT_MS) { job.join() }
        }
        pollJob = null

        try {
            if (transport.isOpen) {
                gracefulEndForDisconnect()
                writeMessage(V1Message.Outgoing.Disconnect())
                delay(COMMAND_DELAY_MS)
                transport.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Error during V1 session stop: ${e.message}")
        }

        accumulator.reset()
        _exerciseData.value = null
        _deviceIdentity.value = null
        _degradedReason.value = null
        _sessionState.value = SessionState.Disconnected
        logger.i(TAG, "V1 session stopped")
    }

    /**
     * Brings the equipment to a safe stopped state, then waits for the MCU to confirm it's ready to
     * drop the USB link, before [stop] disconnects. Three phases:
     *
     * 1. **Belt halt (belt machines only, safety-critical):** a bare `WORKOUT_MODE=IDLE` write does
     *    NOT stop the belt — the firmware treats the idle transition as advisory, so the belt keeps
     *    running until the user hits the physical Stop key (the user-reported bug). We command belt
     *    speed to 0 *and* `PAUSE` (both halt the belt) and confirm the `KPH` read-back reaches 0
     *    first ([haltBeltConfirmed]).
     * 2. **Clean end:** write `WORKOUT_MODE=IDLE` (and `GRADE=0` on incline-capable belt machines so
     *    they don't park raised). This tells the MCU the run is over so it runs its end-of-workout
     *    housekeeping — which we never used to do, so we'd disconnect while it still thought a workout
     *    was live.
     * 3. **Ready-to-disconnect wait:** poll `IS_READY_TO_DISCONNECT` until the MCU asserts it
     *    (bounded — a wedged MCU must not hang teardown). Closing the bus before the MCU is ready
     *    leaves its USB state inconsistent until a full re-enumeration, which is why a run that ended
     *    on the console could previously only be recovered by force-stopping the app.
     */
    private suspend fun gracefulEndForDisconnect() {
        val isBelt = detectedDeviceType.isBeltBased
        if (isBelt) haltBeltConfirmed("disconnect")

        // Clean end (write-only): tell the MCU the run is over so it does its end-of-workout
        // housekeeping. GRADE=0 on belt machines so an incline trainer doesn't park raised.
        logger.i(TAG, "Disconnect cleanup: writing WORKOUT_MODE=IDLE${if (isBelt) ", GRADE=0" else ""}")
        writeMessage(V1Message.Outgoing.ReadWriteData(
            writeFields = buildMap {
                put(V1DataField.WORKOUT_MODE, WorkoutMode.IDLE.raw)
                if (isBelt) put(V1DataField.GRADE, 0f)
            },
        ))
        delay(COMMAND_DELAY_MS)

        repeat(READY_POLL_ATTEMPTS) {
            val response = sendReadWrite(readFields = setOf(V1DataField.IS_READY_TO_DISCONNECT))
            val ready = response?.fields?.get(V1DataField.IS_READY_TO_DISCONNECT)
            if (ready != null && ready >= READY_TO_DISCONNECT_TRUE) {
                logger.i(TAG, "MCU ready to disconnect")
                return
            }
            delay(READY_POLL_MS)
        }
        logger.w(TAG, "MCU never asserted IS_READY_TO_DISCONNECT within ${READY_POLL_ATTEMPTS * READY_POLL_MS}ms — proceeding")
    }

    /**
     * Belt-machine halt loop: command `KPH=0` + `PAUSE` once, then poll the `KPH` read-back until the
     * MCU acknowledges speed 0 (or we run out of attempts). Belt machines only — callers gate on
     * [DeviceType.isBeltBased].
     */
    private suspend fun haltBeltConfirmed(reason: String): Boolean {
        logger.i(TAG, "Belt halt requested ($reason): writing KPH=0 and WORKOUT_MODE=PAUSE")
        repeat(BELT_HALT_CONFIRM_ATTEMPTS) { attempt ->
            val response = sendReadWrite(
                writeFields = if (attempt == 0) mapOf(
                    V1DataField.KPH to 0f,
                    V1DataField.WORKOUT_MODE to WorkoutMode.PAUSE.raw,
                ) else emptyMap(),
                readFields = setOf(V1DataField.KPH),
            )
            val commandedKph = response?.fields?.get(V1DataField.KPH)
            logger.d(TAG, "Belt halt KPH readback ($reason, attempt ${attempt + 1}): $commandedKph")
            if (commandedKph != null && commandedKph <= BELT_STOPPED_KPH) {
                logger.i(TAG, "Belt stopped confirmed ($reason, KPH=$commandedKph)")
                return true
            }
            delay(STATE_CONFIRM_POLL_MS)
        }
        logger.w(TAG, "Belt halt timed out ($reason) after $BELT_HALT_CONFIRM_ATTEMPTS attempts — KPH=0 + PAUSE was sent")
        return false
    }

    /**
     * Stops QZ's workout while leaving a V1 belt console in PAUSE.
     *
     * This matches the console's working Pause control exactly. Some V1 treadmill controllers
     * ignore the combined KPH=0 + PAUSE write and reject a subsequent IDLE transition, whereas a
     * PAUSE-only write reliably stops the belt.
     */
    private suspend fun stopWorkoutSafely() {
        logger.i(TAG, "StopWorkout requested: writing PAUSE-only belt stop")

        // Stop supersedes any target changes which have not reached the MCU yet. Holding
        // commandMutex prevents a later Resume from being queued until this sequence completes.
        pendingWriteMutex.withLock {
            if (pendingWriteFields.isNotEmpty()) {
                logger.i(TAG, "StopWorkout: discarding ${pendingWriteFields.size} queued write(s) before belt halt")
            }
            pendingWriteFields = emptyMap()
        }
        lastSentSpeed = 0f
        sendReadWrite(
            writeFields = mapOf(V1DataField.WORKOUT_MODE to WorkoutMode.PAUSE.raw),
            readFields = pollFields,
        )
    }

    override suspend fun identify(): DeviceIdentity? {
        try {
            transport.open()
            transport.clearBuffer()
            handshake()
            return _deviceIdentity.value
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            logger.e(TAG, "Identify failed", e)
            return null
        } finally {
            try { transport.close() } catch (_: Exception) {}
        }
    }

    override suspend fun calibrate() {
        try {
            transport.open()
            transport.clearBuffer()
            handshake()

            // Calibration runs from idle — no RUNNING mode needed.
            // Connect → handshake → calibrate commands at 4s intervals → disconnect.
            runCalibration()
        } finally {
            try { transport.close() } catch (_: Exception) {}
        }
    }

    override suspend fun writeFeature(command: DeviceCommand) {
        commandMutex.withLock {
            writeFeatureSerialized(command)
        }
    }

    private suspend fun writeFeatureSerialized(command: DeviceCommand) {
        if (command is DeviceCommand.CalibrateIncline) {
            if (_sessionState.value !is SessionState.Streaming) {
                throw IllegalStateException("Not connected")
            }
            val deferred = CompletableDeferred<Unit>()
            pendingCalibration = deferred
            deferred.await()
            return
        }

        if (_sessionState.value !is SessionState.Streaming) return

        logger.i(TAG, "Command requested: ${command::class.simpleName}")
        when (command) {
            is DeviceCommand.PauseWorkout, is DeviceCommand.StopWorkout -> appRequestedPause = true
            is DeviceCommand.ResumeWorkout -> appRequestedPause = false
            else -> {}
        }
        if (command is DeviceCommand.StopWorkout && detectedDeviceType.isBeltBased) {
            ioMutex.withLock { stopWorkoutSafely() }
            return
        }

        val fields = commandToFields(command)

        pendingWriteMutex.withLock {
            pendingWriteFields = pendingWriteFields + fields
        }
    }

    internal fun commandToFields(command: DeviceCommand): Map<V1DataField, Float> = when (command) {
        is DeviceCommand.SetResistance -> {
            // Reads and writes must share a scale. Once the board proves it reports GEAR we
            // display gear (1..MaxGear), so a resistance command has to set the gear too -
            // otherwise ERG and simulated-gradient control from Zwift/MyWhoosh would compute
            // a delta on the gear scale and apply it on the coarser brake scale.
            //
            // [gearSeen] alone is not enough on a board whose GEAR field cannot be polled. The
            // S22i is that board: GEAR had to be dropped from periodicReadFields because it
            // corrupted every later field offset (see V1DataField.periodicReadFields and
            // docs/read-budget.md), so gearSeen can never latch and every SetResistance fell
            // through to RESISTANCE - which this board silently ignores as a write. Measured
            // 2026-09-02: with iFIT holding the USB claim, SetGear 1->9->3 was acknowledged in
            // ~120 ms each (MaxGear=24 == MAX_RESISTANCE_LEVEL), iFIT never wrote RESISTANCE at
            // all, and on handback QZ read resistance=0 while the machine sat at gear 3. So on a
            // bike that *declares* GEAR we address the brake by GEAR from the handshake on,
            // without needing a readback to prove it first.
            // 2026-09-02, measured twice on the S22i: taking this branch on [gearAddressed] alone
            // resets the controller. The GEAR write goes out, the board stops answering, the next
            // ten poll writes fail with `transferred=-1`, and the USB device re-enumerates about
            // 40 s later (devnum bumps; QZ reconnects by itself, no power cycle). A FAN_STATE write
            // - field 98, thirteen bitmask sections against GEAR's four - goes through the exact
            // same encoder and is accepted, so the frame shape is not the problem: field 26 alone
            // is. Reading it inside the periodic set corrupts every field after it, which is the
            // same defect seen from the other end. Both are what a wrong [sizeBytes] looks like.
            // 2026-09-02, [probeFieldSizes] settled it: a single-field read of GEAR came back
            // `08 0D 02 02 00 00 27 01 01 07 21 19 83` - declared length 13, so **8 data bytes**,
            // against the 1 this table declares. (Checksum verifies, and the same probe reproduced
            // KPH/RESISTANCE at 2 and MAX_RESISTANCE_LEVEL at 1, so the frame is being read right.)
            // A 1-byte write is therefore seven bytes short of the frame the MCU is parsing, and a
            // 1-byte read shifts every field behind it - one cause, both symptoms.
            // 2026-09-02, [benchTick] then swept the eight bytes and the gate comes off: byte 4 is
            // the gear, an 8-byte write is accepted without ever dropping the board, and
            // RESISTANCE = 260 x (gear - 1) held on every gear tried. See V1DataField.GEAR.
            // The gate is now "does the board declare GEAR at all", which is what [gearAddressed]
            // answers at handshake - so the brake is addressable from the first command, with no
            // dependency on a readback landing first.
            if (gearAddressed || gearSeen) {
                val maxGear = deviceInfo.maxResistance.takeIf { it > 0 } ?: command.level
                val gear = command.level.coerceIn(1, maxGear)
                lastCommandedGear = gear
                mapOf(V1DataField.GEAR to gear.toFloat())
            } else {
                mapOf(V1DataField.RESISTANCE to resistance.levelToRaw(command.level).toFloat())
            }
        }
        is DeviceCommand.SetIncline -> {
            lastSentGrade = roundToStep(command.percent, deviceInfo.inclineStep)
            mapOf(V1DataField.GRADE to lastSentGrade)
        }
        is DeviceCommand.SetTargetSpeed -> {
            lastSentSpeed = command.kph
            mapOf(V1DataField.KPH to command.kph)
        }
        is DeviceCommand.AdjustIncline -> {
            lastSentGrade += if (command.increase) deviceInfo.inclineStep else -deviceInfo.inclineStep
            lastSentGrade = lastSentGrade.coerceIn(deviceInfo.minIncline, deviceInfo.maxIncline)
            mapOf(V1DataField.GRADE to lastSentGrade)
        }
        is DeviceCommand.AdjustSpeed -> {
            lastSentSpeed += if (command.increase) deviceInfo.speedStep else -deviceInfo.speedStep
            lastSentSpeed = lastSentSpeed.coerceIn(0f, deviceInfo.maxSpeed)
            mapOf(V1DataField.KPH to lastSentSpeed)
        }
        is DeviceCommand.SetTargetPower -> {
            mapOf(
                V1DataField.WATT_GOAL to command.watts.toFloat(),
                V1DataField.IS_CONSTANT_WATTS_MODE to 1f,
            )
        }
        is DeviceCommand.PauseWorkout -> {
            mapOf(V1DataField.WORKOUT_MODE to WorkoutMode.PAUSE.raw)
        }
        is DeviceCommand.ResumeWorkout -> {
            buildMap {
                if (supportsIdleLockout()) {
                    put(V1DataField.IDLE_MODE_LOCKOUT, FIELD_DISABLED)
                }
                put(V1DataField.WORKOUT_MODE, WorkoutMode.RUNNING.raw)
            }
        }
        // V1 belt machines handle this as a confirmed sequence in writeFeatureSerialized. For
        // non-belt V1 equipment, retain the historical stop-as-pause behavior.
        is DeviceCommand.StopWorkout -> mapOf(V1DataField.WORKOUT_MODE to WorkoutMode.PAUSE.raw)
        is DeviceCommand.CalibrateIncline -> emptyMap()
        is DeviceCommand.SetFanSpeed -> {
            logger.i(TAG, "SetFanSpeed requested by the app: level=${command.level}")
            mapOf(V1DataField.FAN_STATE to command.level.toFloat())
        }
        is DeviceCommand.SetVolume -> mapOf(V1DataField.VOLUME to command.level.toFloat())
        is DeviceCommand.SetGear -> {
            lastCommandedGear = command.gear
            mapOf(V1DataField.GEAR to command.gear.toFloat())
        }
        is DeviceCommand.SetDistanceGoal -> mapOf(V1DataField.DISTANCE_GOAL to command.meters.toFloat())
        is DeviceCommand.SetWarmupTimeout -> mapOf(V1DataField.WARMUP_TIMEOUT to command.seconds.toFloat())
        is DeviceCommand.SetCooldownTimeout -> mapOf(V1DataField.COOLDOWN_TIMEOUT to command.seconds.toFloat())
        is DeviceCommand.SetPauseTimeout -> mapOf(V1DataField.PAUSE_TIMEOUT to command.seconds.toFloat())
        is DeviceCommand.SetWarmUpMode -> mapOf(V1DataField.WORKOUT_MODE to WorkoutMode.WARM_UP.raw)
        is DeviceCommand.SetCoolDownMode -> mapOf(V1DataField.WORKOUT_MODE to WorkoutMode.COOL_DOWN.raw)
        is DeviceCommand.SetErgMode -> mapOf(V1DataField.IS_CONSTANT_WATTS_MODE to if (command.enable) 1f else 0f)
    }

    private suspend fun handshake() {
        // 0. DeviceInfo (from MAIN) → serialNumber, softwareVersion, and the real equipment
        //    device ID: the MCU echoes its own device type in byte 0 of the response.
        // DeviceInfo is the gatekeeper: every real controller answers it, and we need its sw /
        // supportedBitFields / equipment id to do anything useful. With no Connect step after it,
        // this is also where we detect "nothing is responding" — so a missing/garbled response is a
        // hard failure rather than something we paper over with defaults.
        val deviceInfo = sendAndAwait(V1Message.Outgoing.DeviceInfo()) as? V1Message.Incoming.DeviceInfoResponse
            ?: throw IllegalStateException("No DeviceInfo response — controller not responding")
        softwareVersion = deviceInfo.softwareVersion
        hardwareVersion = deviceInfo.hardwareVersion
        serialNumber = deviceInfo.serialNumber
        supportedBitFields = deviceInfo.supportedBitFields
        val equipmentDeviceId = deviceInfo.deviceId.takeIf { it in V1Message.EQUIPMENT_DEVICE_IDS }
            ?: V1Message.DEVICE_FITNESS_BIKE
        logger.i(
            TAG,
            "Device info: sw=$softwareVersion, hw=$hardwareVersion, serial=$serialNumber, " +
                "equipmentDeviceId=$equipmentDeviceId, supportedBitFields=${supportedBitFields.size} " +
                "${supportedBitFields.sorted()}",
        )
        // A bike whose board declares the GEAR field is gear-addressed: its brake is a 1..MaxGear
        // selector and RESISTANCE is only a readback of where that selector put it. Decide it here
        // rather than waiting for a polled GEAR, so the very first SetResistance already lands on
        // the brake instead of on a RESISTANCE write this board ignores.
        gearAddressed = (equipmentDeviceId == V1Message.DEVICE_SPIN_BIKE ||
            equipmentDeviceId == V1Message.DEVICE_FITNESS_BIKE) &&
            V1DataField.GEAR.fieldIndex in supportedBitFields
        if (gearAddressed) {
            logger.i(
                TAG,
                "Gear-addressed bike (equipmentDeviceId=$equipmentDeviceId, GEAR bit " +
                    "${V1DataField.GEAR.fieldIndex} declared, MaxGear ${this.deviceInfo.maxResistance}); " +
                    "resistance commands drive GEAR as an 8-byte write, and GEAR is read on its " +
                    "own single-field poll",
            )
        }
        pollFields = computePollFields(supportedBitFields)
        pollKeypad = supportedBitFields.isEmpty() ||
            V1DataField.KEY_OBJECT.fieldIndex in supportedBitFields
        logger.i(TAG, "Keypad polling ${if (pollKeypad) "enabled" else "disabled"} " +
            "(KEY_OBJECT bit ${V1DataField.KEY_OBJECT.fieldIndex})")
        detectedDeviceType = DeviceDatabase.deviceTypeFromEquipmentId(equipmentDeviceId)
        logger.d(TAG, "Detected device type: $detectedDeviceType")

        delay(COMMAND_DELAY_MS)

        // 1. SupportedCommands → the request opcodes this controller accepts; gates every optional
        //    step below. We deliberately send NO Connect command first: the stock console firmware
        //    never sends one (it brings the link up at the transport layer), and sending our legacy
        //    Connect makes some controllers — the NordicTrack S15i spin bike — stop answering this
        //    and the following meta-queries, which then wedges the USB link. Addressed to the
        //    equipment device id, mirroring the stock bring-up. If the controller doesn't answer (or
        //    doesn't list a command) we skip the optional steps and go straight to the data poll,
        //    exactly as the stock firmware does — sending a controller a command it doesn't
        //    implement wedges it.
        val supportedCommands = querySupportedCommands(equipmentDeviceId)

        delay(COMMAND_DELAY_MS)

        // 2. SystemInfo → partNumber, model (skipped if the controller doesn't declare it)
        if (isCommandSupported(supportedCommands, V1Message.CMD_SYSTEM_INFO)) {
            val systemInfo = sendAndAwait(V1Message.Outgoing.SystemInfo())
            if (systemInfo is V1Message.Incoming.SystemInfoResponse) {
                partNumber = systemInfo.partNumber
                model = systemInfo.model
                logger.d(TAG, "System info: partNumber=$partNumber, model=$model")
                powerCurveIndex = DeviceDatabase.powerCurveIndexForPartNumber(partNumber)
                if (powerCurveIndex != null) {
                    logger.i(TAG, "Power curve table: $powerCurveIndex (from part number $partNumber)")
                }
            } else {
                logger.w(TAG, "Expected SystemInfoResponse, got: $systemInfo")
            }
            delay(COMMAND_DELAY_MS)
        } else {
            logger.i(TAG, "Controller doesn't support SystemInfo — skipping (power-curve lookup unavailable)")
        }

        // 3. VersionInfo → masterLibraryVersion (skipped if the controller doesn't declare it)
        if (isCommandSupported(supportedCommands, V1Message.CMD_VERSION_INFO)) {
            val versionInfo = sendAndAwait(V1Message.Outgoing.VersionInfo())
            if (versionInfo is V1Message.Incoming.VersionInfoResponse) {
                masterLibraryVersion = versionInfo.masterLibraryVersion
                logger.d(TAG, "Version info: masterLib=$masterLibraryVersion, build=${versionInfo.masterLibraryBuild}")
            } else {
                logger.w(TAG, "Expected VersionInfoResponse, got: $versionInfo")
            }
            delay(COMMAND_DELAY_MS)
        } else {
            logger.i(TAG, "Controller doesn't support VersionInfo — skipping")
        }

        _deviceIdentity.value = DeviceIdentity(
            serialNumber = serialNumber.toString(),
            firmwareVersion = softwareVersion.toString(),
            hardwareVersion = hardwareVersion.toString(),
            model = model.toString(),
            partNumber = partNumber.toString(),
        )

        // 4. VerifySecurity (only if SW version > 75 and the controller declares it — the security
        //    hash is derived from SystemInfo/VersionInfo values, so a controller that omits those
        //    can't be unlocked this way and doesn't ask to be).
        if (softwareVersion > 75 && isCommandSupported(supportedCommands, V1Message.CMD_VERIFY_SECURITY)) {
            verifySecurity()
            delay(COMMAND_DELAY_MS)
        } else {
            logger.d(TAG, "Skipping security verification (sw=$softwareVersion, declared=${supportedCommands?.contains(V1Message.CMD_VERIFY_SECURITY) ?: false})")
        }

        // 5. Read startup fields (device limits + equipment stats)
        readStartupFields(equipmentDeviceId)
    }

    /**
     * Asks the controller which request command opcodes it accepts. Returns the declared set, or
     * `null` if the controller didn't answer (or returned something unparseable). Callers treat
     * `null` (and any command not in the set) as "not supported, skip it" via [isCommandSupported]
     * — matching the stock console, which only sends a command the controller lists and otherwise
     * goes straight to the data poll. Addressed to the equipment device id.
     */
    private suspend fun querySupportedCommands(equipmentDeviceId: Int): Set<Int>? {
        val response = sendAndAwait(V1Message.Outgoing.SupportedCommands(equipmentDeviceId))
        return if (response is V1Message.Incoming.SupportedCommandsResponse) {
            logger.i(TAG, "Supported commands: ${response.commandIds.sorted().joinToString { "0x%02X".format(it) }}")
            response.commandIds
        } else {
            logger.w(TAG, "SupportedCommands query failed ($response) — skipping optional commands, going straight to poll")
            null
        }
    }

    /** True only when [supportedCommands] was read and declares [commandId]; `null`/unlisted → skip. */
    private fun isCommandSupported(supportedCommands: Set<Int>?, commandId: Int): Boolean =
        supportedCommands != null && commandId in supportedCommands

    private suspend fun verifySecurity() {
        val hash = V1Security.calculateHash(serialNumber, partNumber, model)
        val secretKey = masterLibraryVersion * 8
        val response = sendAndAwait(V1Message.Outgoing.VerifySecurity(hash = hash, secretKey = secretKey))

        if (response is V1Message.Incoming.SecurityResponse) {
            if (!response.isUnlocked) {
                throw IllegalStateException("Security verification failed (key=${response.unlockedKey})")
            }
            logger.i(TAG, "Security verified (key=${response.unlockedKey})")
        } else {
            logger.w(TAG, "Unexpected security response: $response")
        }
    }

    private suspend fun readStartupFields(equipmentDeviceId: Int) {
        val response = sendReadWrite(readFields = V1DataField.startupReadFields)
        if (response == null || response.status != V1Message.STATUS_DONE || response.fields.isEmpty()) {
            logger.d(TAG, "Startup field read returned no data: $response")
            return
        }
        val fields = response.fields
        capabilities = V1Capabilities(
            maxGrade = fields[V1DataField.MAX_GRADE],
            minGrade = fields[V1DataField.MIN_GRADE],
            maxKph = fields[V1DataField.MAX_KPH],
            minKph = fields[V1DataField.MIN_KPH],
            maxResistance = fields[V1DataField.MAX_RESISTANCE_LEVEL]?.toInt()?.takeIf { it > 0 },
            equipmentDeviceId = equipmentDeviceId,
        )
        logger.i(TAG, "Capabilities: $capabilities")
        // TOTAL_TIME / MOTOR_TOTAL_DISTANCE units are device-dependent: bikes report seconds and
        // metres, but belt machines (e.g. the NordicTrack 2950) report milliseconds and millimetres
        // — which, read as raw s/m, would show a ~13-year runtime and an 833,757 km odometer. Scale
        // belt-machine values to seconds/metres so the lifetime stats read sanely. (Empirical from
        // observed hardware; a plausibility heuristic can't work because a lightly-used ms-device is
        // indistinguishable from a heavily-used s-device — only the device type separates them.)
        val lifetimeScale = if (detectedDeviceType.isBeltBased) 1000 else 1
        val eqHours = fields[V1DataField.TOTAL_TIME]?.toLong()?.let { it / lifetimeScale }
        val eqDist = fields[V1DataField.MOTOR_TOTAL_DISTANCE]?.let { it / lifetimeScale }
        _deviceIdentity.value = _deviceIdentity.value?.copy(equipmentHours = eqHours, equipmentDistance = eqDist)
        logger.i(TAG, "Equipment stats: totalTime=${eqHours}s, totalDistance=${eqDist}m")
        val probeIndices = probeFieldIndices()
        probeFieldSizes(V1DataField.entries.filter { it.fieldIndex in probeIndices })
    }

    /**
     * Which field indices the startup width probe covers: everything the controller says it
     * supports, so one session's log carries the board's whole real table. Falls back to the
     * poll set plus [V1DataField.GEAR] on a board that declared no bitmask.
     */
    private fun probeFieldIndices(): Set<Int> =
        supportedBitFields.ifEmpty { pollFields.map { it.fieldIndex }.toSet() + V1DataField.GEAR.fieldIndex }

    /**
     * Reads [fields] **one at a time** and logs each raw response frame.
     *
     * A single-field read cannot be thrown off by a wrong width in an earlier field, so the frame's
     * own declared length (byte 1) is direct evidence of how many data bytes this controller carries
     * for that field — which is exactly what [V1DataField.sizeBytes] only guesses. A response frame
     * is `[deviceId][declaredLen][command][status][data…][checksum]`, so the field's true width is
     * `declaredLen − 5`. Measured on the S22i 2026-09-02: every field matched its declared
     * [V1DataField.sizeBytes] except `GEAR`, which came back **8 bytes** against the 1 the table
     * claims — which is why reading it corrupts every later field and writing it wedges the board.
     *
     * Diagnostic only: it costs a few extra reads once per session and writes nothing.
     */
    private suspend fun probeFieldSizes(fields: List<V1DataField>) {
        for (field in fields) {
            writeMessage(V1Message.Outgoing.ReadWriteData(readFields = setOf(field)))
            delay(READ_DELAY_MS)
            val raw = readPacketOrNull()
            if (raw == null) {
                logger.i(TAG, "Field probe ${field.name}(${field.fieldIndex}): no response")
                continue
            }
            val actualSize = raw.payloadSize()
            val decoded = V1Codec.decodeSingleDataResponse(raw, setOf(field))?.fields?.get(field)
            logger.i(
                TAG,
                "Field probe ${field.name}(${field.fieldIndex}) assumedSize=${field.sizeBytes} " +
                    "actualSize=$actualSize${if (actualSize == field.sizeBytes) "" else " MISMATCH"} " +
                    "decoded=$decoded declaredLen=${raw.declaredLength()} raw=${raw.toHexDump()}",
            )
        }
    }

    /**
     * A ReadWriteData frame assembled from raw field indices and raw data bytes, bypassing
     * [V1DataField] entirely. Mirrors `V1Codec.encodeReadWriteData`: the payload is the write
     * bitmask and its data followed by the read bitmask, each `[numSections][one mask per section]`.
     *
     * Raw indices rather than enum entries because the enum's widths are the thing under suspicion:
     * `GEAR` measured 8 data bytes on the S22i against the 1 it declares, and the indices the enum
     * has no entry for at all (23-25) have never been looked at.
     */
    private fun buildRawReadWrite(
        writeIndex: Int? = null,
        writeData: ByteArray = ByteArray(0),
        readIndices: List<Int> = emptyList(),
    ): ByteArray {
        fun bitmask(indices: List<Int>): ByteArray {
            val highest = indices.maxOrNull() ?: return byteArrayOf(0)
            val sections = (highest / 8) + 1
            val out = ByteArray(sections + 1)
            out[0] = sections.toByte()
            for (i in indices) out[1 + i / 8] = (out[1 + i / 8].toInt() or (1 shl (i % 8))).toByte()
            return out
        }
        val writePayload = if (writeIndex == null) byteArrayOf(0) else bitmask(listOf(writeIndex)) + writeData
        val payload = writePayload + bitmask(readIndices)
        val total = payload.size + V1_REQUEST_OVERHEAD
        val packet = ByteArray(total)
        packet[0] = V1Message.DEVICE_MAIN.toByte()
        packet[1] = total.toByte()
        packet[2] = BENCH_CMD_READ_WRITE_DATA
        payload.copyInto(packet, 3)
        packet[total - 1] = V1Codec.checksum(packet.copyOfRange(0, total - 1))
        return packet
    }

    /**
     * A protocol bench driven from a file, so a byte-layout sweep can be run over ADB with no
     * further build. Nothing happens unless the file exists, and it is deleted as soon as it is
     * read. One line in [BENCH_COMMAND_FILE], run on the next poll tick inside the same mutex hold:
     *
     *  - `read <index> [<index>…]` — one ReadWriteData read of those raw field indices.
     *  - `write <index> <hex byte>…` — write those exact data bytes to that field index and read
     *    the field back in the same frame.
     *  - `sample <index> <count> <intervalMs>` — repeat a single-field read, which is how a byte
     *    that tracks the machine is told apart from a constant.
     *
     * A malformed write can knock this controller off the USB bus. It re-enumerates by itself in
     * about 40 s and the session reconnects, so a bad sweep step costs a wait, not a power cycle.
     */
    private suspend fun benchTick() {
        val file = File(BENCH_COMMAND_FILE)
        if (!file.isFile) return
        val line = try {
            file.readText().trim()
        } catch (e: Exception) {
            logger.w(TAG, "Bench: unreadable command file: ${e.message}")
            return
        } finally {
            // Delete before executing: a command that wedges the board must not re-run every poll.
            runCatching { file.delete() }
        }

        val parts = line.split(' ', '\t', '\n').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        try {
            when (parts[0].lowercase()) {
                "read" -> benchRead(parts.drop(1).map { it.toInt() }, "read")
                "write" -> benchWrite(parts[1].toInt(), parts.drop(2).map { it.toInt(16).toByte() }.toByteArray())
                "sample" -> {
                    val index = parts[1].toInt()
                    val count = parts[2].toInt().coerceIn(1, 120)
                    val intervalMs = parts[3].toLong().coerceIn(50L, 5_000L)
                    for (i in 1..count) {
                        benchRead(listOf(index), "sample $i/$count")
                        delay(intervalMs)
                    }
                }
                else -> logger.w(TAG, "Bench: unknown command '$line'")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Bench: '$line' failed: ${e.message}")
        }
    }

    private suspend fun benchRead(indices: List<Int>, label: String) {
        transport.write(buildRawReadWrite(readIndices = indices))
        delay(READ_DELAY_MS)
        val raw = readPacketOrNull()
        logger.i(
            TAG,
            "Bench $label fields=$indices -> " +
                (if (raw == null) "no response" else "dataBytes=${raw.payloadSize()} raw=${raw.toHexDump()}"),
        )
    }

    private suspend fun benchWrite(index: Int, data: ByteArray) {
        val packet = buildRawReadWrite(writeIndex = index, writeData = data, readIndices = listOf(index))
        logger.i(TAG, "Bench write field=$index data=${data.toHexDump()} tx=${packet.toHexDump()}")
        transport.write(packet)
        delay(READ_DELAY_MS)
        val raw = readPacketOrNull()
        logger.i(
            TAG,
            "Bench write field=$index -> " +
                (if (raw == null) "no response" else "dataBytes=${raw.payloadSize()} raw=${raw.toHexDump()}"),
        )
    }

    /**
     * Console init, done while the console is still IDLE (before the workout transition).
     * Branches by device type:
     *
     * - **Treadmill / incline trainer**: only `REQUIRE_START_REQUESTED` is asserted, and even that
     *   only if the device supports the bitfield. `IDLE_MODE_LOCKOUT` is deliberately left alone —
     *   on a belt-driven machine the MCU itself gates belt motion on the physical Start key, and
     *   locking out idle-mode on top of that would fight the safety interlock.
     * - **Bike / elliptical / rower**: both `REQUIRE_START_REQUESTED` and `IDLE_MODE_LOCKOUT` are
     *   asserted (if supported). `IDLE_MODE_LOCKOUT=ENABLED` here keeps a Zwift-style session
     *   streaming when the rider briefly stops pedalling — without it the MCU auto-pauses the
     *   workout. [transitionToActive] re-disables it immediately before writing
     *   `WORKOUT_MODE=RUNNING`, which the firmware requires.
     */
    private suspend fun prepareConsole() {
        val isTreadmill = detectedDeviceType == DeviceType.TREADMILL
        val supportsRequireStart = supportedBitFields.isEmpty() ||
            V1DataField.REQUIRE_START_REQUESTED.fieldIndex in supportedBitFields
        val supportsIdleLockout = supportedBitFields.isEmpty() ||
            V1DataField.IDLE_MODE_LOCKOUT.fieldIndex in supportedBitFields

        if (supportsRequireStart) {
            logger.i(TAG, "Console init: writing REQUIRE_START_REQUESTED=ENABLED")
            writeConsoleField(V1DataField.REQUIRE_START_REQUESTED, FIELD_ENABLED)
        }
        if (!isTreadmill && supportsIdleLockout) {
            logger.i(TAG, "Console init: writing IDLE_MODE_LOCKOUT=ENABLED")
            writeConsoleField(V1DataField.IDLE_MODE_LOCKOUT, FIELD_ENABLED)
        }
    }

    private suspend fun writeConsoleField(field: V1DataField, value: Float) {
        sendReadWrite(writeFields = mapOf(field to value), readFields = setOf(field))
        delay(COMMAND_DELAY_MS)
    }

    /**
     * Builds the per-loop read set from [V1DataField.periodicReadFields] intersected with the
     * device's self-declared [supportedBitFields]. We trust the device's declaration: if it didn't
     * claim a field, the MCU won't include bytes for it in the response, and asking anyway would
     * misalign every later field's offset (the bug that produced -10595 kcal / 139 km on the
     * NordicTrack 2950 Argon screenshot).
     *
     * Falls back to the full periodicReadFields set if [supportedBitFields] is empty (handshake
     * couldn't parse the device's bitmask) — that preserves the pre-fix behavior for devices
     * we've always worked with, and the warning makes the fallback visible.
     */
    private fun computePollFields(supportedBitFields: Set<Int>): Set<V1DataField> {
        if (supportedBitFields.isEmpty()) {
            logger.w(
                TAG,
                "Device declared no supportedBitFields; polling the full periodicReadFields set. " +
                    "If the MCU omits any of these fields the decoder will misalign — watch for isTruncated.",
            )
            return V1DataField.periodicReadFields
        }
        val filtered = V1DataField.periodicReadFields.filterTo(mutableSetOf()) { it.fieldIndex in supportedBitFields }
        val omitted = V1DataField.periodicReadFields - filtered
        if (omitted.isNotEmpty()) {
            logger.i(
                TAG,
                "Filtering ${omitted.size} unsupported field(s) from poll: ${omitted.joinToString { it.name }}",
            )
        }
        return filtered
    }

    /**
     * Brings the console up to the workout-active state the way the firmware expects. Two paths,
     * because treadmills and aerobic machines have fundamentally different start safety:
     *
     * - **Treadmill / incline trainer**: remain in IDLE. Real V1 treadmill firmware starts its belt
     *   when WARM_UP is written, so connection alone must never make that transition. ResumeWorkout
     *   is the sole software action that writes RUNNING.
     * - **Bike / elliptical / rower**: drive the state machine ourselves —
     *   `IDLE → WARM_UP(10) → RUNNING(2)` with confirmation polling. `IDLE_MODE_LOCKOUT` must be
     *   disabled immediately before writing RUNNING (the firmware refuses the RUNNING transition
     *   while idle-mode is locked, even though we needed it locked through [prepareConsole] for
     *   the streaming-without-auto-pause behaviour). If the MCU never confirms a step we log a
     *   warning and continue degraded — that warning is the thing to look for in logs when
     *   resistance / speed controls don't respond.
     */
    private suspend fun transitionToActive() {
        if (detectedDeviceType == DeviceType.TREADMILL) {
            val currentMode = accumulator.snapshot().workoutMode
                ?.let { WorkoutMode.fromRaw(it.toFloat()) }
                ?: WorkoutMode.UNKNOWN
            logger.i(
                TAG,
                "V1 startup: detectedDeviceType=$detectedDeviceType, current WORKOUT_MODE=$currentMode; " +
                    "skipping transitionToActive and leaving the treadmill stopped in IDLE",
            )
            _degradedReason.value = null
            return
        }

        if (supportsIdleLockout()) {
            writeConsoleField(V1DataField.IDLE_MODE_LOCKOUT, FIELD_DISABLED)
        }
        writeAndConfirmWorkoutMode(WorkoutMode.WARM_UP) { it != WorkoutMode.IDLE }
        val running = writeAndConfirmWorkoutMode(WorkoutMode.RUNNING) { it == WorkoutMode.RUNNING }
        logger.i(TAG, "Console state: IDLE → WARM_UP → ${running ?: WorkoutMode.UNKNOWN}")
        _degradedReason.value =
            if (running == WorkoutMode.RUNNING) null
            else "The console didn't confirm the workout started — resistance/speed may not respond"
    }

    private suspend fun writeAndConfirmWorkoutMode(target: WorkoutMode, accept: (WorkoutMode) -> Boolean): WorkoutMode? {
        logger.i(TAG, "Writing WORKOUT_MODE=$target")
        repeat((STATE_CONFIRM_TIMEOUT_MS / STATE_CONFIRM_POLL_MS).toInt()) { attempt ->
            // Assert the target on the first attempt; subsequent attempts just poll the read-back.
            val response = sendReadWrite(
                writeFields = if (attempt == 0) mapOf(V1DataField.WORKOUT_MODE to target.raw) else emptyMap(),
                readFields = setOf(V1DataField.WORKOUT_MODE),
            )
            val mode = response?.fields?.get(V1DataField.WORKOUT_MODE)?.let { WorkoutMode.fromRaw(it) }
            if (mode != null && accept(mode)) return mode
            delay(STATE_CONFIRM_POLL_MS)
        }
        logger.w(TAG, "Console didn't reach $target — workout may be inactive; continuing")
        return null
    }

    /**
     * Re-drives the workout transition when the console has left RUNNING on its own. Costs no
     * extra bus traffic: WORKOUT_MODE is already in [V1DataField.periodicReadFields], so this
     * reads the mode the current poll just decoded.
     *
     * Only IDLE and PAUSE are recovered - those are what the console's own timeouts produce.
     * COOL_DOWN is a deliberate end-of-workout state and DMK means the safety key is out;
     * forcing RUNNING out of either would fight the machine. Belt machines are excluded
     * outright: on a treadmill a software-written RUNNING is what starts the belt, and
     * ResumeWorkout remains the only thing allowed to do that.
     */
    private suspend fun restoreWorkoutStateIfNeeded() {
        if (detectedDeviceType.isBeltBased || appRequestedPause) return
        val mode = accumulator.snapshot().workoutMode?.let { WorkoutMode.fromRaw(it) } ?: return
        if (mode == WorkoutMode.RUNNING) {
            workoutWatchdogBackoffMs = WORKOUT_WATCHDOG_MIN_BACKOFF_MS
            workoutWatchdogNextAttemptMs = 0L
            return
        }
        if (mode != WorkoutMode.IDLE && mode != WorkoutMode.PAUSE) return

        val now = System.currentTimeMillis()
        if (now < workoutWatchdogNextAttemptMs) return
        // Claim the next slot before trying, not after: a console that refuses to come back must
        // cost one attempt a minute, not one per poll tick for the rest of the ride.
        workoutWatchdogNextAttemptMs = now + workoutWatchdogBackoffMs
        workoutWatchdogBackoffMs =
            (workoutWatchdogBackoffMs * 2).coerceAtMost(WORKOUT_WATCHDOG_MAX_BACKOFF_MS)

        logger.i(TAG, "Console left RUNNING (now $mode) - re-driving the workout transition")
        transitionToActive()
    }

    private fun supportsIdleLockout(): Boolean =
        supportedBitFields.isEmpty() || V1DataField.IDLE_MODE_LOCKOUT.fieldIndex in supportedBitFields

    /**
     * Sends one ReadWriteData (writes [writeFields], requests [readFields]) and decodes the single-
     * packet response, returning it (or null on no/garbled response). Used for the startup-field read
     * and the workout-state writes/confirmations — not the poll loop, which reads the multi-packet
     * [V1DataField.periodicReadFields] via [pollOnce].
     */
    private suspend fun sendReadWrite(
        writeFields: Map<V1DataField, Float> = emptyMap(),
        readFields: Set<V1DataField> = emptySet(),
    ): V1Message.Incoming.DataResponse? {
        logSafetyWrites(writeFields)
        writeMessage(V1Message.Outgoing.ReadWriteData(writeFields = writeFields, readFields = readFields))
        delay(READ_DELAY_MS)
        val raw = readPacketOrNull() ?: return null
        return V1Codec.decodeSingleDataResponse(raw, readFields.ifEmpty { V1DataField.periodicReadFields })
    }

    private fun logSafetyWrites(writeFields: Map<V1DataField, Float>) {
        writeFields[V1DataField.WORKOUT_MODE]?.let {
            logger.i(TAG, "Explicit WORKOUT_MODE write: ${WorkoutMode.fromRaw(it)} ($it)")
        }
        writeFields[V1DataField.KPH]?.let { logger.i(TAG, "Explicit KPH write: $it") }
        writeFields[V1DataField.IDLE_MODE_LOCKOUT]?.let {
            logger.i(TAG, "Explicit IDLE_MODE_LOCKOUT write: $it")
        }
        writeFields[V1DataField.REQUIRE_START_REQUESTED]?.let {
            logger.i(TAG, "Explicit REQUIRE_START_REQUESTED write: $it")
        }
    }

    private suspend fun writeMessage(message: V1Message.Outgoing) {
        for (packet in V1Codec.encode(message)) transport.write(packet)
    }

    private fun startPollLoop() {
        pollJob = scope.launch {
            while (isActive && _sessionState.value is SessionState.Streaming) {
                val calibDeferred = pendingCalibration
                if (calibDeferred != null) {
                    pendingCalibration = null
                    try {
                        runCalibration()
                        calibDeferred.complete(Unit)
                    } catch (e: CancellationException) {
                        calibDeferred.cancel(e)
                        throw e
                    } catch (e: Exception) {
                        calibDeferred.completeExceptionally(e)
                    }
                } else {
                    try {
                        pollOnce()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        consecutivePollErrors++
                        logger.w(TAG, "Poll error ($consecutivePollErrors/$MAX_CONSECUTIVE_POLL_ERRORS): ${e.message}")
                        if (consecutivePollErrors >= MAX_CONSECUTIVE_POLL_ERRORS) {
                            _sessionState.value = SessionState.Error("Repeated poll failures", e)
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }

            if (_sessionState.value is SessionState.Streaming) {
                logger.w(TAG, "Poll loop ended unexpectedly")
                _sessionState.value = SessionState.Disconnected
            }
        }
    }

    private suspend fun pollOnce() = ioMutex.withLock {
        pollOnceLocked()
        if (pollKeypad) pollKeypadOnce()
        if (gearAddressed) pollGearOnce()
        restoreWorkoutStateIfNeeded()
        benchTick()
    }

    /**
     * One GEAR-only read, chasing the main poll inside the same mutex hold — the same shape
     * [pollKeypadOnce] uses, and for the same reason: GEAR is 8 bytes, which is a seventh of this
     * console's response budget, and putting it in [pollFields] would push the reply past the size
     * at which this MCU silently drops the tail.
     *
     * This is what lets [gearSeen] latch. Under the old 1-byte declaration GEAR could not be polled
     * at all without corrupting every field behind it, so the flag could never become true and
     * every SetResistance fell through to a RESISTANCE write the board ignores. That was the whole
     * fault: not a missing capability, a wrong field width.
     */
    private suspend fun pollGearOnce() {
        val response = try {
            sendReadWrite(readFields = GEAR_READ_FIELDS)
        } catch (e: Exception) {
            logger.w(TAG, "Gear poll failed: ${e.message}")
            return
        }
        if (response == null || response.status != V1Message.STATUS_DONE) return
        response.fields[V1DataField.GEAR]?.let { applyDataResponse(mapOf(V1DataField.GEAR to it)) }
    }

    /**
     * One KEY_OBJECT-only read, chasing the main poll inside the same mutex hold. The response
     * decodes onto [V1Message.Incoming.DataResponse.keyObject], not the fields map, so it never
     * goes through the truncation/empty-fields checks that assume the [pollFields] shape.
     * KEY_OBJECT reports the currently-pressed key and 0 on release, so this read is also what
     * clears the edge detector in [handleKeyObject] between presses.
     */
    private suspend fun pollKeypadOnce() {
        val response = try {
            sendReadWrite(readFields = KEYPAD_READ_FIELDS)
        } catch (e: Exception) {
            logger.w(TAG, "Keypad poll failed: ${e.message}")
            return
        }
        if (response == null || response.status != V1Message.STATUS_DONE) return
        handleKeyObject(response.keyObject)
    }

    private suspend fun pollOnceLocked() {
        val writeFields: Map<V1DataField, Float>
        pendingWriteMutex.withLock {
            writeFields = pendingWriteFields
            pendingWriteFields = emptyMap()
        }
        logSafetyWrites(writeFields)

        // ReadWriteData targets DEVICE_MAIN (0x02) — FITNESS_BIKE (0x07) returns DEV_NOT_SUPPORTED.
        // pollFields is periodicReadFields ∩ supportedBitFields so the response payload size matches
        // the decoder's positional read; see V1Codec.decodeDataResponseForFields.
        writeMessage(V1Message.Outgoing.ReadWriteData(
            writeFields = writeFields,
            readFields = pollFields,
        ))

        delay(READ_DELAY_MS)

        // No timeout here on purpose: the poll loop is steady-state and just waits for the next reply.
        val firstPacket = transport.readPacket()
        if (firstPacket == null) {
            if (writeFields.isNotEmpty()) {
                pendingWriteMutex.withLock {
                    pendingWriteFields = writeFields + pendingWriteFields
                }
            }
            return
        }

        val decoded = try {
            readResponse(firstPacket, pollFields)
        } catch (e: Exception) {
            logger.w(TAG, "Malformed response (${firstPacket.size} bytes): ${e.message}")
            // Re-queue write fields so commands aren't lost
            if (writeFields.isNotEmpty()) {
                pendingWriteMutex.withLock {
                    pendingWriteFields = writeFields + pendingWriteFields
                }
            }
            return
        }
        if (decoded is V1Message.Incoming.DataResponse) {
            if (decoded.status == V1Message.STATUS_SECURITY_BLOCK) {
                logger.w(TAG, "Security block — re-verifying")
                if (writeFields.isNotEmpty()) {
                    pendingWriteMutex.withLock {
                        pendingWriteFields = writeFields + pendingWriteFields
                    }
                }
                verifySecurity()
                return
            }
            if (decoded.status != V1Message.STATUS_DONE) {
                return
            }

            if (decoded.fields.isEmpty()) {
                logger.w(TAG, "DataResponse OK but empty fields (payload size mismatch)")
                return
            }

            // Edge-triggered: log once when the response shape stops matching the request shape,
            // not every 100ms. Means the MCU is supplying a different field set than its DeviceInfo
            // bitmask declared — keep going (lenient decode produced what it could) but surface it.
            if (decoded.isTruncated && !lastTruncatedSeen) {
                logger.w(
                    TAG,
                    "DataResponse payload size doesn't match the requested ${pollFields.size}-field shape " +
                        "(expected ${pollFields.sumOf { it.sizeBytes }}B of field data, " +
                        "got ${firstPacket.payloadSize()}B (declared ${firstPacket.declaredLength()}B, " +
                        "USB frame ${firstPacket.size}B); raw=[${firstPacket.toHexDump()}]; " +
                        "requested=[${pollFields.sortedBy { it.fieldIndex }.joinToString { it.name }}], " +
                        "decoded ${decoded.fields.size}=[${decoded.fields.keys.joinToString { it.name }}], " +
                        "supportedBitFields=${supportedBitFields.sorted()}) — later field offsets may be unreliable.",
                )
            } else if (!decoded.isTruncated && lastTruncatedSeen) {
                logger.i(TAG, "DataResponse payload size now matches the requested field shape again.")
            }
            // Baseline for the read budget: log the size relationship once per session even when
            // the shape matches, so a good poll and a truncated one can be compared directly.
            if (!loggedPayloadBaseline) {
                loggedPayloadBaseline = true
                logger.i(
                    TAG,
                    "Poll size baseline: ${pollFields.size} fields, " +
                        "${pollFields.sumOf { it.sizeBytes }}B of field data requested, " +
                        "got ${firstPacket.payloadSize()}B (declared ${firstPacket.declaredLength()}B, " +
                        "USB frame ${firstPacket.size}B), truncated=${decoded.isTruncated}; " +
                        "requested=[${pollFields.sortedBy { it.fieldIndex }.joinToString { "${it.name}:${it.sizeBytes}" }}]; " +
                        "raw=[${firstPacket.toHexDump()}]",
                )
            }
            lastTruncatedSeen = decoded.isTruncated

            applyDataResponse(decoded.fields)
            decoded.keyObject?.let { handleKeyObject(it) }
            estimatePowerIfNeeded()
            consecutivePollErrors = 0
            _exerciseData.value = accumulator.snapshot()

            val now = System.currentTimeMillis()
            if (now - lastLogTimeMs >= 1000L) {
                lastLogTimeMs = now
                val snap = _exerciseData.value
                if (snap != null) {
                    logger.d(TAG, "power=${snap.power}W cadence=${snap.cadence}rpm speed=${snap.speed}kph resistance=${snap.resistance} brake=$lastBrakeLevel gear=$lastCommandedGear "
                        + "gearSeen=$gearSeen gearAddressed=$gearAddressed incline=${snap.incline}%")
                }
            }
        }
    }

    private fun applyDataResponse(fields: Map<V1DataField, Float>) {
        for ((field, value) in fields) {
            when (field) {
                V1DataField.WATTS -> accumulator.updatePower(value.toInt())
                V1DataField.RPM -> {
                    val prev = accumulator.snapshot().cadence
                    accumulator.updateCadence(value.toInt())
                    if ((prev == null || prev == 0) && value.toInt() > 0) {
                        logger.d(TAG, "Cadence went non-zero: ${value.toInt()} rpm")
                    }
                }
                // Speed source is device-type-dependent: belt machines report belt speed in KPH
                // (ACTUAL_KPH stays 0); other machines report a virtual speed in ACTUAL_KPH and
                // leave KPH as an unused setpoint (a bike has no commandable speed), so we don't
                // surface it as a target — no meaningless blue speed arrow on a bike.
                V1DataField.ACTUAL_KPH -> if (!detectedDeviceType.isBeltBased) accumulator.updateSpeed(value)
                V1DataField.KPH -> if (detectedDeviceType.isBeltBased) accumulator.updateSpeed(value)
                // The console's +/- buttons are GearUp/GearDown and drive GEAR (1..24 here,
                // MaxGear from the board). GEAR is the number printed on the console, and it
                // moves by exactly one per press. Field RESISTANCE is a separate, coarser
                // internal brake level (0..14 measured on the S22i) that the console never
                // displays, so driving the UI from it dropped roughly nine of every
                // twenty-four presses and looked like a button needing two taps.
                // Fall back to RESISTANCE until the board actually reports a gear, so a
                // console without one behaves exactly as before.
                V1DataField.GEAR -> {
                    val gear = value.toInt()
                    if (gear > 0) {
                        if (!gearSeen) {
                            gearSeen = true
                            logger.i(TAG, "GEAR reported by board (=$gear); driving resistance from gear, "
                                + "brake level field was $lastBrakeLevel")
                        }
                        accumulator.updateResistance(gear)
                    }
                }
                V1DataField.RESISTANCE -> {
                    lastBrakeLevel = resistance.rawToLevel(value.toInt())
                    // Showing our own last commanded gear here instead was tried on 2026-09-02 and
                    // reverted: with nothing commanded yet it leaves resistance null, so the UI and
                    // every FTMS/DIRCON reader see no resistance at all rather than a stale one.
                    if (!gearSeen) accumulator.updateResistance(lastBrakeLevel)
                }
                V1DataField.ACTUAL_INCLINE -> accumulator.updateIncline(value)
                V1DataField.GRADE -> accumulator.updateTargetIncline(value)
                // Grip HR is a noisy analog contact reading — gate + smooth it, and clear (null) on
                // contact loss. External BLE HRMs bypass this and are merged in the orchestrator.
                V1DataField.PULSE -> accumulator.updateHeartRate(gripHeartRate.update(value.toInt()))
                // CURRENT_DISTANCE is meters on the wire, but ExerciseData.distance — and every
                // consumer (FTMS ×1000→m, dashboard "KM", ride recorder distanceKm) — is kilometers.
                // Convert here, or distance reads 1000× high.
                V1DataField.CURRENT_DISTANCE -> accumulator.updateDistance(value / 1000f)
                V1DataField.CURRENT_CALORIES -> accumulator.updateCalories(value.toInt())
                V1DataField.CURRENT_TIME -> accumulator.updateElapsedTime(value.toLong())
                V1DataField.WORKOUT_MODE -> {
                    val mode = value.toInt()
                    val previousMode = accumulator.snapshot().workoutMode
                    if (previousMode != mode) {
                        when (WorkoutMode.fromRaw(mode)) {
                            WorkoutMode.PAUSE -> accumulator.pause()
                            WorkoutMode.RUNNING -> {
                                accumulator.resume()
                                accumulator.startTimer()
                            }
                            else -> {}
                        }
                    }
                    accumulator.updateWorkoutMode(mode)
                }
                V1DataField.VERTICAL_METER_GAIN -> accumulator.updateVerticalGain(value)
                V1DataField.VERTICAL_METER_NET -> accumulator.updateVerticalNet(value)
                V1DataField.AVERAGE_WATTS -> accumulator.updateAverageWatts(value.toInt())
                V1DataField.AVERAGE_GRADE -> accumulator.updateAverageIncline(value)
                V1DataField.LAP_TIME -> accumulator.updateLapTime(value.toLong())
                V1DataField.RECOVERABLE_PAUSED_TIME -> accumulator.updatePausedTime(value.toLong())
                V1DataField.START_REQUESTED -> accumulator.updateStartRequested(value.toInt() != 0)
                V1DataField.GOAL_TIME -> accumulator.updateGoalTime(value.toLong())
                V1DataField.STROKES -> accumulator.updateStrokeCount(value.toInt())
                V1DataField.STROKES_PER_MINUTE -> accumulator.updateStrokeRate(value.toInt())
                V1DataField.FIVE_HUNDRED_SPLIT -> accumulator.updateSplitTime(value.toInt())
                V1DataField.AVG_FIVE_HUNDRED_SPLIT -> accumulator.updateAvgSplitTime(value.toInt())
                V1DataField.FAN_STATE -> {
                    val raw = value.toInt()
                    // The console reports fan state as a small enum, and we do not yet know its
                    // ordering (Off/Low/Med/High/Auto in some order, possibly with a wrap). Log
                    // every change so a press of the physical fan keys can be read off logcat and
                    // lined up against what the panel shows. Edge-triggered, so an idle fan is silent.
                    if (raw != lastFanRaw) {
                        logger.i(TAG, "FAN_STATE changed: $lastFanRaw -> $raw")
                        lastFanRaw = raw
                    }
                    accumulator.updateFanSpeed(raw)
                }
                // KEY_OBJECT is decoded onto DataResponse.keyObject and handled in handleKeyObject(),
                // so it never reaches this map — this case only keeps the `when` exhaustive.
                V1DataField.KEY_OBJECT,
                V1DataField.RUNNING_TIME,
                V1DataField.DISTANCE,
                V1DataField.CALORIES,
                V1DataField.MAX_RESISTANCE_LEVEL,
                V1DataField.WATT_GOAL,
                V1DataField.IDLE_MODE_LOCKOUT,
                V1DataField.REQUIRE_START_REQUESTED,
                V1DataField.VOLUME,
                V1DataField.PAUSE_TIMEOUT,
                V1DataField.WARMUP_TIMEOUT,
                V1DataField.COOLDOWN_TIMEOUT,
                V1DataField.DISTANCE_GOAL,
                V1DataField.IS_CONSTANT_WATTS_MODE,
                V1DataField.MAX_GRADE,
                V1DataField.MIN_GRADE,
                V1DataField.MAX_KPH,
                V1DataField.MIN_KPH,
                V1DataField.MAX_PULSE,
                V1DataField.MAX_RPM,
                V1DataField.SYSTEM_UNITS,
                V1DataField.MOTOR_TOTAL_DISTANCE,
                V1DataField.TOTAL_TIME,
                V1DataField.IS_READY_TO_DISCONNECT -> { /* write-only, capability, or unprocessed fields */ }
            }
        }
    }

    /**
     * Emits a [ConsoleKey] on each fresh press of the console membrane keypad. KEY_OBJECT reports the
     * *currently-pressed* key (and 0 on release), so we edge-detect: emit when the code changes to a
     * new non-zero value. The equipment's own MCU acts on every one of these keys directly (changing
     * resistance/incline/speed, transitioning the workout state machine on START/STOP, etc.) and the
     * new state flows up through normal polling. Exception, measured on the S22i: while an app
     * drives the brake over FTMS the MCU leaves the resistance +/- keys unacted, so
     * FitProDeviceService subscribes to this stream and closes that loop itself.
     */
    private fun handleKeyObject(keyObject: KeyObject?) {
        val code = keyObject?.code ?: 0
        if (code == lastKeyCode) return
        lastKeyCode = code
        if (code == 0) return
        val key = fitProKeyToConsoleKey(code)
        logger.d(TAG, "Console keypad: code=$code held=${keyObject?.timeHeld ?: 0}ms${key?.let { " ($it)" } ?: ""}")
        key?.let { _consoleKeyPresses.tryEmit(it) }
    }

    private fun fitProKeyToConsoleKey(code: Int): ConsoleKey? = when (code) {
        KEY_START -> ConsoleKey.START
        KEY_STOP -> ConsoleKey.STOP
        KEY_SPEED_UP -> ConsoleKey.SPEED_UP
        KEY_SPEED_DOWN -> ConsoleKey.SPEED_DOWN
        KEY_INCLINE_UP -> ConsoleKey.INCLINE_UP
        KEY_INCLINE_DOWN -> ConsoleKey.INCLINE_DOWN
        // GEAR_UP/DOWN map to resistance — on bike consoles the +/- buttons are the resistance/gear
        // selector and there's no separate "gear" the app tracks.
        KEY_RESISTANCE_UP, KEY_GEAR_UP -> ConsoleKey.RESISTANCE_UP
        KEY_RESISTANCE_DOWN, KEY_GEAR_DOWN -> ConsoleKey.RESISTANCE_DOWN
        else -> null // fan / volume / etc. — not mapped (yet)
    }

    private fun estimatePowerIfNeeded() {
        val snapshot = accumulator.snapshot()
        if (snapshot.power != null && snapshot.power != 0) return // MCU provides power
        val speed = snapshot.speed ?: return
        val resistance = snapshot.resistance ?: return
        val maxRes = deviceInfo.maxResistance
        if (maxRes <= 0) return

        val estimated = powerCurveIndex?.let {
            PowerEstimator.estimate(it, speed, resistance, maxRes, deviceInfo.type)
        } ?: PowerEstimator.estimateFallback(speed, resistance, maxRes)

        if (estimated != null && estimated > 0) {
            accumulator.updatePower(estimated)
        }
    }

    private suspend fun runCalibration() {
        logger.i(TAG, "Starting incline calibration")
        var attempts = 0
        while (attempts < MAX_CALIBRATION_ATTEMPTS) {
            val response = sendAndAwait(V1Message.Outgoing.Calibrate())
            logger.d(TAG, "Calibration poll $attempts: $response")
            if (response is V1Message.Incoming.GenericResponse) {
                when (response.status) {
                    V1Message.STATUS_DONE -> {
                        logger.i(TAG, "Incline calibration complete")
                        return
                    }
                    V1Message.STATUS_IN_PROGRESS -> {
                        attempts++
                        delay(CALIBRATION_POLL_MS)
                    }
                    V1Message.STATUS_SECURITY_BLOCK -> {
                        logger.w(TAG, "Security block during calibration — re-verifying")
                        verifySecurity()
                        attempts++
                    }
                    else -> throw IllegalStateException("Calibration failed: status=${response.status}")
                }
            } else {
                throw IllegalStateException("Unexpected calibration response: $response")
            }
        }
        throw IllegalStateException("Calibration timed out after $MAX_CALIBRATION_ATTEMPTS attempts")
    }

    private suspend fun sendAndAwait(message: V1Message.Outgoing): V1Message.Incoming? {
        writeMessage(message)
        delay(READ_DELAY_MS)
        val firstPacket = readPacketOrNull() ?: return null
        return readResponse(firstPacket)
    }

    /** [transport.readPacket] with a safety timeout — a non-responsive MCU must not hang the session. */
    private suspend fun readPacketOrNull(): ByteArray? =
        withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { transport.readPacket() }

    private suspend fun readResponse(
        firstPacket: ByteArray,
        dataResponseFields: Set<V1DataField>? = null,
    ): V1Message.Incoming? {
        if (V1Codec.isMultiPacketHeader(firstPacket)) {
            val expected = V1Codec.expectedPacketCount(firstPacket)
            val packets = mutableListOf(firstPacket)
            repeat(expected) {
                val dataPacket = transport.readPacket() ?: return null
                packets.add(dataPacket)
            }
            return V1Codec.decode(packets, dataResponseFields)
        }
        return V1Codec.decodeSingle(firstPacket, dataResponseFields)
    }

    /** Length the MCU declares in byte[1]. USB pads every read to 64, so [ByteArray.size] cannot be used. */
    private fun ByteArray.declaredLength(): Int = if (size > 1) this[1].toInt() and 0xFF else size

    /** Field-data bytes in a response: the declared length less the 4-byte header and the checksum. */
    private fun ByteArray.payloadSize(): Int = (declaredLength() - V1_HEADER_AND_CHECKSUM).coerceAtLeast(0)

    /**
     * Hex of the whole USB frame, padding included. The point of the dump is that the declared
     * length is the thing under suspicion, so it must not also be what decides how much to print.
     */
    private fun ByteArray.toHexDump(): String = joinToString(" ") { "%02X".format(it) }

    private fun roundToStep(value: Float, step: Float): Float =
        (value / step).roundToInt() * step

    companion object {
        private const val TAG = "V1Session"

        // A response is [device, length, command, status, payload..., checksum]: 4 header bytes
        // plus the trailing checksum sit outside the field data. Mirrors V1Codec.HEADER_SIZE.
        private const val V1_HEADER_AND_CHECKSUM = 5

        // A *request* has no status byte, so its overhead is three header bytes plus the checksum.
        private const val V1_REQUEST_OVERHEAD = 4
        private const val BENCH_CMD_READ_WRITE_DATA: Byte = 0x02 // mirrors V1Codec's private constant
        // The app's own external files dir: no storage permission at any API level, writable by
        // `adb shell`, so the whole sweep runs from the desktop without another build.
        private const val BENCH_COMMAND_FILE =
            "/sdcard/Android/data/org.cagnulen.qdomyoszwift/files/fitpro-bench.txt"
        private const val POLL_INTERVAL_MS = 100L
        private const val COMMAND_DELAY_MS = 100L
        private const val READ_DELAY_MS = 0L
        // Safety timeout for a single MCU response — it normally replies immediately; if it ever
        // doesn't, fail/degrade gracefully instead of hanging the session.
        private const val RESPONSE_TIMEOUT_MS = 1000L
        private const val MAX_CONSECUTIVE_POLL_ERRORS = 10
        private const val CALIBRATION_POLL_MS = 4000L // 4-second poll interval during calibration
        private const val MAX_CALIBRATION_ATTEMPTS = 60 // 4-minute timeout at 4s intervals

        // Confirming a WORKOUT_MODE transition: re-read WORKOUT_MODE every STATE_CONFIRM_POLL_MS,
        // for up to STATE_CONFIRM_TIMEOUT_MS, before giving up and continuing degraded.
        private const val STATE_CONFIRM_POLL_MS = 150L
        private const val STATE_CONFIRM_TIMEOUT_MS = 5_000L

        // Workout-state watchdog backoff. The first drop-out is recovered on the poll that sees
        // it; each attempt that does not get the console back to RUNNING doubles the wait, so a
        // console that is refusing (safety key out, firmware fault) is retried once a minute
        // instead of ten times a second. Reset as soon as RUNNING is read back.
        private const val WORKOUT_WATCHDOG_MIN_BACKOFF_MS = 5_000L
        private const val WORKOUT_WATCHDOG_MAX_BACKOFF_MS = 60_000L

        // Teardown: bound how long stop() waits for the poll loop to exit before touching the transport.
        private const val POLL_JOIN_TIMEOUT_MS = 500L

        // Belt-machine halt on stop: command KPH=0 + PAUSE, then poll the KPH read-back until it
        // reaches ~0 (confirming the MCU accepted the halt) before disconnecting.
        private const val BELT_HALT_CONFIRM_ATTEMPTS = 8
        private const val BELT_STOPPED_KPH = 0.1f

        // Graceful teardown: after the clean-end write, poll IS_READY_TO_DISCONNECT until the MCU
        // asserts it (BYTE field, so ~1 = ready), bounded so a wedged MCU can't hang teardown.
        private const val READY_TO_DISCONNECT_TRUE = 0.5f
        private const val READY_POLL_MS = 150L
        private const val READY_POLL_ATTEMPTS = 10

        // Console-init field values: 1 = ENABLED (REQUIRE_START_REQUESTED) / LOCKED (IDLE_MODE_LOCKOUT),
        // 0 = DISABLED / UNLOCKED.
        private const val FIELD_ENABLED = 1f
        private const val FIELD_DISABLED = 0f

        // KEY_OBJECT key codes for the console-keypad buttons we surface as [ConsoleKey] events.
        // Hyperborea acts on none of them directly — the MCU does the work and the resulting
        // state flows up through the WORKOUT_MODE poll.
        private const val KEY_STOP = 1
        private const val KEY_START = 2
        private const val KEY_SPEED_UP = 3
        private const val KEY_SPEED_DOWN = 4
        private const val KEY_INCLINE_UP = 5
        private const val KEY_INCLINE_DOWN = 6
        private const val KEY_RESISTANCE_UP = 7
        private const val KEY_RESISTANCE_DOWN = 8
        private const val KEY_GEAR_UP = 9
        private const val KEY_GEAR_DOWN = 10

        private val KEYPAD_READ_FIELDS = setOf(V1DataField.KEY_OBJECT)
        private val GEAR_READ_FIELDS = setOf(V1DataField.GEAR)
    }
}
