package io.github.yuroyami.kitetorrent.settings

/**
 * A collection of session-setting overrides. The pure-Kotlin port of libtorrent's
 * `settings_pack` (`settings_pack.hpp` / `settings_pack.cpp`).
 *
 * A [SettingsPack] stores only the settings that have been *explicitly* set on it
 * (mirroring the C++ `m_strings` / `m_ints` / `m_bools` vectors). Reading a setting
 * that has not been overridden falls back to the libtorrent default value for that
 * key (see [StringSettingDefaults], [IntSettingDefaults], [BoolSettingDefaults]).
 *
 * Keys are the 16-bit values from [StringSetting], [IntSetting] and [BoolSetting];
 * the top two bits select the value type (see [SettingType]). Passing a key of the
 * wrong type to a typed accessor is a no-op for setters and returns the type's zero
 * value for getters, matching the defensive behaviour of the C++ accessors.
 *
 * Unlike the C++ version, which keeps its vectors sorted for binary search, this port
 * uses plain hash maps. The semantics (override-or-default, last-write-wins) are
 * identical; only the storage layout differs.
 */
class SettingsPack {

    private val strings = HashMap<Int, String>()
    private val ints = HashMap<Int, Int>()
    private val bools = HashMap<Int, Boolean>()

    // ---- string accessors ------------------------------------------------------

    /** Override the string setting [name] (a [StringSetting] key). No-op if [name] isn't a string key. */
    fun setString(name: Int, value: String) {
        if (!SettingType.isString(name)) return
        strings[name] = value
    }

    /**
     * The current value of string setting [name]: the override if set, otherwise the
     * libtorrent default (which may be the empty string if the default is "no value").
     */
    fun getString(name: Int): String {
        if (!SettingType.isString(name)) return ""
        strings[name]?.let { return it }
        val idx = SettingType.index(name)
        return if (idx < StringSettingDefaults.DEFAULTS.size)
            (StringSettingDefaults.DEFAULTS[idx] ?: "")
        else ""
    }

    // ---- int accessors ---------------------------------------------------------

    /** Override the int setting [name] (an [IntSetting] key). No-op if [name] isn't an int key. */
    fun setInt(name: Int, value: Int) {
        if (!SettingType.isInt(name)) return
        ints[name] = value
    }

    /** The current value of int setting [name]: the override if set, otherwise the default. */
    fun getInt(name: Int): Int {
        if (!SettingType.isInt(name)) return 0
        ints[name]?.let { return it }
        val idx = SettingType.index(name)
        return if (idx < IntSettingDefaults.DEFAULTS.size) IntSettingDefaults.DEFAULTS[idx] else 0
    }

    // ---- bool accessors --------------------------------------------------------

    /** Override the bool setting [name] (a [BoolSetting] key). No-op if [name] isn't a bool key. */
    fun setBool(name: Int, value: Boolean) {
        if (!SettingType.isBool(name)) return
        bools[name] = value
    }

    /** The current value of bool setting [name]: the override if set, otherwise the default. */
    fun getBool(name: Int): Boolean {
        if (!SettingType.isBool(name)) return false
        bools[name]?.let { return it }
        val idx = SettingType.index(name)
        return if (idx < BoolSettingDefaults.DEFAULTS.size) BoolSettingDefaults.DEFAULTS[idx] else false
    }

    // ---- pack management (port of has_val / clear) -----------------------------

    /** True if [name] has an explicit override in this pack (port of `settings_pack::has_val`). */
    fun hasVal(name: Int): Boolean = when {
        SettingType.isString(name) -> strings.containsKey(name)
        SettingType.isInt(name) -> ints.containsKey(name)
        SettingType.isBool(name) -> bools.containsKey(name)
        else -> false
    }

    /** Remove all overrides (port of `settings_pack::clear()`). */
    fun clear() {
        strings.clear()
        ints.clear()
        bools.clear()
    }

    /** Remove the override for a single setting [name] (port of `settings_pack::clear(int)`). */
    fun clear(name: Int) {
        when {
            SettingType.isString(name) -> strings.remove(name)
            SettingType.isInt(name) -> ints.remove(name)
            SettingType.isBool(name) -> bools.remove(name)
        }
    }

    /** Total number of overrides currently held. */
    val size: Int get() = strings.size + ints.size + bools.size

    /**
     * Visit every override in this pack (port of `settings_pack::for_each`). The
     * callback receives the 16-bit key and the value (`String`, `Int` or `Boolean`).
     */
    fun forEach(action: (name: Int, value: Any) -> Unit) {
        for ((k, v) in strings) action(k, v)
        for ((k, v) in ints) action(k, v)
        for ((k, v) in bools) action(k, v)
    }

    companion object {
        /**
         * A [SettingsPack] with every setting set to its libtorrent default value.
         * The port of the free function `default_settings()` in `settings_pack.cpp`.
         *
         * Like the C++ version, string settings whose default is "no value"
         * (`nullptr`) are *not* added to the pack.
         */
        fun defaults(): SettingsPack {
            val pack = SettingsPack()
            for (i in StringSettingDefaults.DEFAULTS.indices) {
                val d = StringSettingDefaults.DEFAULTS[i] ?: continue
                pack.strings[SettingType.STRING_TYPE_BASE + i] = d
            }
            for (i in IntSettingDefaults.DEFAULTS.indices) {
                pack.ints[SettingType.INT_TYPE_BASE + i] = IntSettingDefaults.DEFAULTS[i]
            }
            for (i in BoolSettingDefaults.DEFAULTS.indices) {
                pack.bools[SettingType.BOOL_TYPE_BASE + i] = BoolSettingDefaults.DEFAULTS[i]
            }
            return pack
        }

        /**
         * Look up the 16-bit setting key for a serialisation [name], or `-1` if the
         * name is unknown. The port of `setting_by_name()` (`settings_pack.cpp`),
         * including the `"peer_tos"` backwards-compatibility alias for `peer_dscp`.
         */
        fun settingByName(name: String): Int {
            for (k in StringSettingDefaults.NAMES.indices) {
                if (StringSettingDefaults.NAMES[k].isNotEmpty() && StringSettingDefaults.NAMES[k] == name)
                    return SettingType.STRING_TYPE_BASE + k
            }
            for (k in IntSettingDefaults.NAMES.indices) {
                if (IntSettingDefaults.NAMES[k].isNotEmpty() && IntSettingDefaults.NAMES[k] == name)
                    return SettingType.INT_TYPE_BASE + k
            }
            for (k in BoolSettingDefaults.NAMES.indices) {
                if (BoolSettingDefaults.NAMES[k].isNotEmpty() && BoolSettingDefaults.NAMES[k] == name)
                    return SettingType.BOOL_TYPE_BASE + k
            }
            if (name == "peer_tos") return IntSetting.PEER_DSCP
            return -1
        }

        /**
         * The serialisation name for a 16-bit setting key [name], or the empty string
         * if the key is out of range. The port of `name_for_setting()`
         * (`settings_pack.cpp`).
         */
        fun nameForSetting(name: Int): String {
            val idx = SettingType.index(name)
            return when {
                SettingType.isString(name) ->
                    if (idx < StringSettingDefaults.NAMES.size) StringSettingDefaults.NAMES[idx] else ""
                SettingType.isInt(name) ->
                    if (idx < IntSettingDefaults.NAMES.size) IntSettingDefaults.NAMES[idx] else ""
                SettingType.isBool(name) ->
                    if (idx < BoolSettingDefaults.NAMES.size) BoolSettingDefaults.NAMES[idx] else ""
                else -> ""
            }
        }
    }
}

/**
 * Umbrella re-export of the most commonly used setting keys, so call sites can write
 * `Settings.ENABLE_DHT` instead of remembering which of the three category objects a
 * key lives in. Every value here is identical to the corresponding constant in
 * [StringSetting] / [IntSetting] / [BoolSetting]; this object adds no new settings.
 */
object Settings {
    // strings
    const val USER_AGENT = StringSetting.USER_AGENT
    const val PEER_FINGERPRINT = StringSetting.PEER_FINGERPRINT
    const val LISTEN_INTERFACES = StringSetting.LISTEN_INTERFACES
    const val DHT_BOOTSTRAP_NODES = StringSetting.DHT_BOOTSTRAP_NODES
    const val PROXY_HOSTNAME = StringSetting.PROXY_HOSTNAME

    // ints
    const val CONNECTIONS_LIMIT = IntSetting.CONNECTIONS_LIMIT
    const val UNCHOKE_SLOTS_LIMIT = IntSetting.UNCHOKE_SLOTS_LIMIT
    const val UPLOAD_RATE_LIMIT = IntSetting.UPLOAD_RATE_LIMIT
    const val DOWNLOAD_RATE_LIMIT = IntSetting.DOWNLOAD_RATE_LIMIT
    const val PEER_TIMEOUT = IntSetting.PEER_TIMEOUT
    const val CHOKING_ALGORITHM = IntSetting.CHOKING_ALGORITHM
    const val SEED_CHOKING_ALGORITHM = IntSetting.SEED_CHOKING_ALGORITHM
    const val DISK_IO_WRITE_MODE = IntSetting.DISK_IO_WRITE_MODE
    const val DISK_IO_READ_MODE = IntSetting.DISK_IO_READ_MODE
    const val DISK_WRITE_MODE = IntSetting.DISK_WRITE_MODE
    const val MIXED_MODE_ALGORITHM = IntSetting.MIXED_MODE_ALGORITHM
    const val OUT_ENC_POLICY = IntSetting.OUT_ENC_POLICY
    const val IN_ENC_POLICY = IntSetting.IN_ENC_POLICY
    const val ALLOWED_ENC_LEVEL = IntSetting.ALLOWED_ENC_LEVEL
    const val PROXY_TYPE = IntSetting.PROXY_TYPE
    const val PROXY_PORT = IntSetting.PROXY_PORT
    const val I2P_PORT = IntSetting.I2P_PORT
    const val ALERT_MASK = IntSetting.ALERT_MASK

    // bools
    const val ENABLE_DHT = BoolSetting.ENABLE_DHT
    const val ENABLE_LSD = BoolSetting.ENABLE_LSD
    const val ENABLE_UPNP = BoolSetting.ENABLE_UPNP
    const val ENABLE_NATPMP = BoolSetting.ENABLE_NATPMP
    const val ANONYMOUS_MODE = BoolSetting.ANONYMOUS_MODE
}
