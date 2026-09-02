package com.nettarion.hyperborea.hardware.fitpro.v1

/**
 * Decoded FitPro V1 `KEY_OBJECT` field (field 7, 14 bytes): the console membrane-keypad state.
 * Byte layout: `code (uint16 LE)`, `rawKey (uint64)`, `timePressed (uint16 LE)`, `timeHeld (uint16 LE)`.
 * `code` is the currently-pressed key (0 = none, e.g. SPEED_UP=3, INCLINE_UP=5, RESISTANCE_UP=7,
 * GEAR_UP=9, …); `timePressed` is a per-press counter; `timeHeld` is how long the key has been held
 * this press, in ms.
 */
data class KeyObject(val code: Int, val timePressed: Int, val timeHeld: Int)

sealed interface V1Message {

    sealed interface Outgoing : V1Message {
        data class SupportedDevices(val deviceId: Int = DEVICE_MAIN) : Outgoing
        data class SupportedCommands(val deviceId: Int = DEVICE_MAIN) : Outgoing
        data class Connect(val deviceId: Int = DEVICE_FITNESS_BIKE) : Outgoing
        data class Disconnect(val deviceId: Int = DEVICE_FITNESS_BIKE) : Outgoing
        data class DeviceInfo(val deviceId: Int = DEVICE_MAIN) : Outgoing
        data class SystemInfo(val deviceId: Int = DEVICE_MAIN) : Outgoing
        data class VersionInfo(val deviceId: Int = DEVICE_MAIN) : Outgoing
        data class VerifySecurity(
            val deviceId: Int = DEVICE_MAIN,
            val hash: ByteArray,
            val secretKey: Int,
        ) : Outgoing {
            override fun equals(other: Any?) = other is VerifySecurity &&
                deviceId == other.deviceId && hash.contentEquals(other.hash) && secretKey == other.secretKey
            override fun hashCode() = 31 * (31 * deviceId + hash.contentHashCode()) + secretKey
        }
        data class ReadWriteData(
            val deviceId: Int = DEVICE_MAIN,
            val writeFields: Map<V1DataField, Float> = emptyMap(),
            val readFields: Set<V1DataField> = emptySet(),
        ) : Outgoing
        data class Calibrate(val deviceId: Int = DEVICE_GRADE) : Outgoing
    }

    sealed interface Incoming : V1Message {
        data class SupportedDevicesResponse(val deviceIds: List<Int>) : Incoming
        /** The set of request command opcodes (e.g. [DEVICE_MAIN]-targeted 0x82 SystemInfo) the controller declares it accepts. */
        data class SupportedCommandsResponse(val commandIds: Set<Int>) : Incoming
        data class ConnectAck(val deviceId: Int) : Incoming
        data class DisconnectAck(val deviceId: Int) : Incoming
        data class DeviceInfoResponse(
            /** The equipment device id the MCU echoes back here (e.g. [DEVICE_FITNESS_BIKE], [DEVICE_TREADMILL]). */
            val deviceId: Int,
            val softwareVersion: Int,
            val hardwareVersion: Int,
            val serialNumber: Int,
            /** Bitfield indices ([V1DataField.fieldIndex]) the device declares it supports. Empty if it couldn't be parsed. */
            val supportedBitFields: Set<Int>,
            val raw: ByteArray,
        ) : Incoming {
            // raw fully determines deviceId/sw/hw/serial/supportedBitFields, so it alone identifies the value.
            override fun equals(other: Any?) = other is DeviceInfoResponse &&
                deviceId == other.deviceId && raw.contentEquals(other.raw)
            override fun hashCode() = 31 * deviceId + raw.contentHashCode()
        }
        data class SystemInfoResponse(
            val partNumber: Int,
            val model: Int,
        ) : Incoming
        data class VersionInfoResponse(
            val masterLibraryVersion: Int,
            val masterLibraryBuild: Int,
        ) : Incoming
        data class SecurityResponse(
            val unlockedKey: Int,
            val isUnlocked: Boolean,
        ) : Incoming
        data class DataResponse(
            val status: Int,
            val fields: Map<V1DataField, Float>,
            val keyObject: KeyObject? = null,
            /**
             * True when the response payload's byte count didn't match what
             * the requested field set demanded — typically the MCU returned
             * fewer fields than we asked for. The lenient decoder will still
             * have produced what it could, but offsets after the first
             * missing field are unreliable, so the caller should treat
             * later fields as suspect.
             */
            val isTruncated: Boolean = false,
        ) : Incoming
        data class GenericResponse(val commandId: Int, val status: Int, val payload: ByteArray) : Incoming {
            override fun equals(other: Any?) = other is GenericResponse &&
                commandId == other.commandId && payload.contentEquals(other.payload)
            override fun hashCode() = 31 * commandId + payload.contentHashCode()
        }
        data class Unknown(val raw: ByteArray) : Incoming {
            override fun equals(other: Any?) = other is Unknown && raw.contentEquals(other.raw)
            override fun hashCode() = raw.contentHashCode()
        }
    }

    companion object {
        const val DEVICE_MAIN = 0x02
        const val DEVICE_PORTAL = 0x03
        const val DEVICE_TREADMILL = 0x04
        const val DEVICE_INCLINE_TRAINER = 0x05
        const val DEVICE_ELLIPTICAL = 0x06
        const val DEVICE_FITNESS_BIKE = 0x07
        const val DEVICE_SPIN_BIKE = 0x08
        const val DEVICE_VERTICAL_ELLIPTICAL = 0x09
        const val DEVICE_FREE_STRIDER = 0x13
        const val DEVICE_ROWER = 0x14
        const val DEVICE_GRADE = 0x42

        /** Equipment device IDs that represent actual exercise machines. */
        val EQUIPMENT_DEVICE_IDS = setOf(
            DEVICE_TREADMILL, DEVICE_INCLINE_TRAINER, DEVICE_ELLIPTICAL,
            DEVICE_FITNESS_BIKE, DEVICE_SPIN_BIKE, DEVICE_VERTICAL_ELLIPTICAL,
            DEVICE_FREE_STRIDER, DEVICE_ROWER,
        )

        const val STATUS_DONE = 0x02
        const val STATUS_IN_PROGRESS = 0x03
        const val STATUS_SECURITY_BLOCK = 0x08

        // Request command opcodes, as reported in a SupportedCommandsResponse. Used to gate the
        // optional handshake steps: a controller that doesn't list a command will wedge the USB
        // link if it's sent one anyway (observed on the NordicTrack S15i spin bike, which omits
        // SystemInfo). These match the CMD_* bytes in V1Codec.
        const val CMD_SYSTEM_INFO = 0x82
        const val CMD_VERSION_INFO = 0x84
        const val CMD_VERIFY_SECURITY = 0x90
    }
}

/**
 * FitPro V1 BitField definitions: field IDs, sizes, and converter types.
 *
 * The fieldIndex determines the section-based bitmask position:
 *   section = fieldIndex / 8
 *   bit     = fieldIndex % 8
 */
enum class V1DataField(val fieldIndex: Int, val sizeBytes: Int, val converter: V1Converter) {
    KPH(0, 2, V1Converter.SPEED),
    GRADE(1, 2, V1Converter.GRADE),
    RESISTANCE(2, 2, V1Converter.RESISTANCE),
    WATTS(3, 2, V1Converter.SHORT),
    CURRENT_DISTANCE(4, 4, V1Converter.INT),
    RPM(5, 2, V1Converter.SHORT),
    DISTANCE(6, 4, V1Converter.INT),
    KEY_OBJECT(7, 14, V1Converter.KEY_OBJECT),
    VOLUME(9, 1, V1Converter.BYTE),
    PULSE(10, 4, V1Converter.PULSE),
    RUNNING_TIME(11, 4, V1Converter.INT),
    WORKOUT_MODE(12, 1, V1Converter.BYTE),
    CALORIES(13, 4, V1Converter.CALORIES),
    LAP_TIME(15, 2, V1Converter.SHORT),
    ACTUAL_KPH(16, 2, V1Converter.SPEED),
    ACTUAL_INCLINE(17, 2, V1Converter.GRADE),
    CURRENT_TIME(20, 4, V1Converter.INT),
    CURRENT_CALORIES(21, 4, V1Converter.CALORIES),
    GOAL_TIME(22, 4, V1Converter.INT),
    // 8 bytes, not the 1 the stock table claims. Measured on the NordicTrack S22i 2026-09-02 by
    // single-field read: `08 0D 02 02 00 00 27 01 01 07 21 19 83` — declared length 13, so 13-5 = 8
    // data bytes. Declaring 1 is what wedged the board on a GEAR write (the MCU parsed seven bytes
    // of the following bitmask as gear data) and corrupted every field behind GEAR on a read.
    // Byte roles, measured by sweeping each one and reading RESISTANCE back:
    //   0 read-only   1 "apply" flag (0 = leave unchanged)   2 derived   3 constant 01
    //   4 THE GEAR, 1..MaxGear    5 gear option (07)    6 derived (MaxGear+10 − gear)   7 MaxGear+1
    // Only byte 4 moves the brake: RESISTANCE = 260 × (gear − 1), exact for gear 1..24, verified
    // predictively (gear 15 → 3640, gear 19 → 4680, both hit on the nose). Gear 0 is invalid and
    // makes the board report RESISTANCE 10000, a sentinel well above the in-range maximum of 5980.
    GEAR(26, 8, V1Converter.GEAR),
    MAX_GRADE(27, 2, V1Converter.GRADE),
    MIN_GRADE(28, 2, V1Converter.GRADE),
    MAX_KPH(30, 2, V1Converter.SPEED),
    MIN_KPH(31, 2, V1Converter.SPEED),
    PAUSE_TIMEOUT(35, 2, V1Converter.SHORT),
    SYSTEM_UNITS(36, 1, V1Converter.BYTE),
    MAX_RESISTANCE_LEVEL(42, 1, V1Converter.BYTE),
    WARMUP_TIMEOUT(46, 2, V1Converter.SHORT),
    MAX_PULSE(49, 1, V1Converter.BYTE),
    AVERAGE_GRADE(52, 2, V1Converter.GRADE),
    AVERAGE_WATTS(54, 2, V1Converter.SHORT),
    MAX_RPM(57, 2, V1Converter.SHORT),
    WATT_GOAL(61, 2, V1Converter.SHORT),
    DISTANCE_GOAL(64, 4, V1Converter.INT),
    COOLDOWN_TIMEOUT(71, 2, V1Converter.SHORT),
    VERTICAL_METER_NET(75, 4, V1Converter.VERTICAL),
    VERTICAL_METER_GAIN(76, 4, V1Converter.VERTICAL),
    IDLE_MODE_LOCKOUT(95, 1, V1Converter.BYTE),
    START_REQUESTED(96, 1, V1Converter.BYTE),
    FAN_STATE(98, 1, V1Converter.BYTE),
    RECOVERABLE_PAUSED_TIME(103, 4, V1Converter.INT),
    REQUIRE_START_REQUESTED(108, 1, V1Converter.BYTE),
    STROKES(109, 2, V1Converter.SHORT),
    STROKES_PER_MINUTE(110, 1, V1Converter.BYTE),
    FIVE_HUNDRED_SPLIT(111, 2, V1Converter.SHORT),
    AVG_FIVE_HUNDRED_SPLIT(112, 2, V1Converter.SHORT),
    IS_READY_TO_DISCONNECT(116, 1, V1Converter.BYTE),
    IS_CONSTANT_WATTS_MODE(119, 1, V1Converter.BYTE),
    MOTOR_TOTAL_DISTANCE(69, 4, V1Converter.INT),
    TOTAL_TIME(70, 4, V1Converter.INT),
    ;

    companion object {
        private val byFieldIndex = entries.associateBy { it.fieldIndex }
        fun fromFieldIndex(index: Int): V1DataField? = byFieldIndex[index]

        /**
         * Fields read each iteration of the polling loop.
         *
         * Deliberately small. A DataResponse carries a fixed-size payload, and at least
         * one controller (NordicTrack S22i, MalataMediatekArgon2) stops at ~58 bytes:
         * ask for more and the surplus fields are silently dropped off the end, with
         * `isTruncated` the only signal. The 30-field list this replaced came to 80
         * bytes on that console and lost its last ten fields -- FAN_STATE among them,
         * which is why the fan always read 0 there.
         *
         * This list is 51 bytes / 20 fields on a bike, once computePollFields() drops
         * the rower entries the capability bitmask doesn't claim.
         *
         * Removed relative to the previous list, and why:
         *   KEY_OBJECT (14 bytes) -- console keypad state. Decoded onto
         *       consoleKeyPresses, which is diagnostic plumbing only; the MCU acts on
         *       every key itself and the result arrives through normal polling, so
         *       nothing downstream drives off it. Costs a quarter of the budget.
         *   GOAL_TIME (4) -- static per-workout config, set before the ride starts.
         *   WARMUP_TIMEOUT (2), COOLDOWN_TIMEOUT (2), VOLUME (1), GEAR (1),
         *   SYSTEM_UNITS (1) -- all fall into applyDataResponse's discard branch;
         *       they were read ten times a second and thrown away.
         */
        val periodicReadFields: Set<V1DataField> = setOf(
            // Sensor data
            WATTS, RPM, PULSE, ACTUAL_KPH, ACTUAL_INCLINE, KPH,
            // Session tracking
            CURRENT_DISTANCE, CURRENT_CALORIES, CURRENT_TIME,
            GRADE, RESISTANCE, WORKOUT_MODE,
            LAP_TIME, AVERAGE_GRADE, AVERAGE_WATTS,
            VERTICAL_METER_NET, VERTICAL_METER_GAIN,
            START_REQUESTED, RECOVERABLE_PAUSED_TIME,
            // Config/target readback.
            // WATT_GOAL (2B), IS_CONSTANT_WATTS_MODE (1B) and IS_READY_TO_DISCONNECT
            // (1B) are deliberately NOT polled: all three are values QZ itself writes
            // and never needs read back, and IS_READY_TO_DISCONNECT still works at
            // teardown, which uses its own single-field read.
            //
            // GEAR is still not polled here, but the reason has changed and the old
            // one is now known to have been a symptom, not a cause. It used to read a
            // constant 0 and leave every field behind it stale -- measured 2026-08-31,
            // six console fan-key presses that audibly changed the fan produced no
            // FAN_STATE change at all, while every field ahead of GEAR's offset stayed
            // correct. That was the wrong sizeBytes: GEAR is 8 bytes, was declared 1,
            // so the decoder walked seven bytes short from GEAR onward. With the width
            // corrected the corruption is gone, but 8 bytes is a seventh of this
            // console's ~58-byte response budget, so GEAR gets its own single-field
            // read in the poll loop instead (see V1Session.pollGearOnce) -- the same
            // shape KEY_OBJECT already uses, and for the same reason.
            FAN_STATE,
            // Rower data
            STROKES, STROKES_PER_MINUTE,
            FIVE_HUNDRED_SPLIT, AVG_FIVE_HUNDRED_SPLIT,
        )

        /** Fields read once at startup — device limits + equipment lifetime stats. */
        val startupReadFields: Set<V1DataField> = setOf(
            MAX_GRADE, MIN_GRADE, MAX_KPH, MIN_KPH, MAX_RESISTANCE_LEVEL,
            MOTOR_TOTAL_DISTANCE, TOTAL_TIME,
        )
    }
}

enum class V1Converter {
    SPEED,
    GRADE,
    RESISTANCE,
    SHORT,
    INT,
    BYTE,
    PULSE,
    CALORIES,
    VERTICAL,
    KEY_OBJECT,
    /** The 8-byte gear struct; see [V1DataField.GEAR]. Decodes to byte 4, encodes into byte 4. */
    GEAR,
}
